package fun.commons.notification4j.it;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import fun.commons.notification4j.entity.NfyaChannel;
import fun.commons.notification4j.entity.NfyaTenant;
import fun.commons.notification4j.mapper.NfyaChannelMapper;
import fun.commons.notification4j.mapper.NfyaTenantMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.net.InetAddress;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 14 步：查询补全（API-MSG-003/005/008 + DICT-001 + STAT-001 + PST-001）。
 * VECTOR: TAG=step14-query
 * 契约：
 * - MSG-003 按 biz_no 查发送结果与投递（T 鉴权；10400 防探测）；
 * - MSG-005 消息详情（T+U；**返回即置已读**；非本人 10400）；
 * - MSG-008 最近 N 条（limit≤10，铃铛下拉）；
 * - DICT-001 字典下发（levels/channel_types/read_status/delivery_status，前端禁硬编码）；
 * - STAT-001 租户概览（today_sent/today_delivered/deliver_success_rate/read_rate_7d/channel_count）；
 * - PST-001 平台概览（tenant_count/active_tenants/today_messages/today_deliveries/deliver_success_rate/dead_count/dead_tenants≤20）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfyQueryCompletionTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.application.name=notification4j-it",
                "nfy.runtime.enable-api=true",
                "spring.flyway.enabled=false",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure",
                "framework4j.tenant.enabled=true",
                "framework4j.tenant.table-prefix=nfya_",
                "framework4j.tenant.ddl-mode=PROVIDED",
                "framework4j.tenant.auth.enabled=true",
                "framework4j.tenant.auth.path=/nfy/api/v1/auth/token",
                "framework4j.tenant.auth.token-type=TENANT",
                "framework4j.tenant.auth.expire-seconds=28800",
                "framework4j.tenant.auth.max-fail=5",
                "framework4j.tenant.auth.lock-minutes=15",
                "framework4j.tenant.platform.client-id=PLATFORM",
                "framework4j.tenant.platform.client-secret=platform-secret-IT",
                "framework4j.redis.enabled=true",
                "framework4j.access-token.enabled=true",
                "framework4j.access-token.secret-key=it_jwt_secret_key_it_jwt_secret_key_123456",
                "framework4j.access-token.hash-salt=it_salt",
                "framework4j.access-token.exclude-path-patterns=/nfy/api/v1/auth/token,/nfy/open/**",
                "framework4j.access-token.policies.TENANT.key=tenant_id",
                "framework4j.access-token.policies.TENANT.expire-time=28800",
                "framework4j.access-token.policies.PLATFORM.key=tenant_id",
                "framework4j.access-token.policies.PLATFORM.expire-time=28800",
                "framework4j.sensitive.enabled=true",
                "framework4j.sensitive.encryption-key=12345678901234567890123456789012"
        })
@AutoConfigureMockMvc
@Tag("step14")
class NfyQueryCompletionTest {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        PG.start();
        REDIS.start();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
    @MapperScan("fun.commons.notification4j.mapper")
    static class TestApp {
        @Bean
        DataSource dataSource() {
            org.springframework.jdbc.datasource.SimpleDriverDataSource ds = new org.springframework.jdbc.datasource.SimpleDriverDataSource();
            ds.setDriverClass(org.postgresql.Driver.class);
            ds.setUrl(PG.getJdbcUrl() + "?stringtype=unspecified");
            ds.setUsername(PG.getUsername());
            ds.setPassword(PG.getPassword());
            return ds;
        }

        @Bean
        org.springframework.data.redis.connection.RedisConnectionFactory redisConnectionFactory() {
            org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory f =
                    new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
            f.afterPropertiesSet();
            lettuceFactory = f;
            return f;
        }

        @Bean
        com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator identifierGenerator() {
            return new com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator();
        }

        @Bean
        org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate(
                org.springframework.data.redis.connection.RedisConnectionFactory factory) {
            return new org.springframework.data.redis.core.StringRedisTemplate(factory);
        }

        @Bean
        fun.commons.notification4j.service.WebhookTargetResolver webhookTargetResolver() {
            return host -> {
                if (host.endsWith("feishu.cn") || host.equals("oapi.dingtalk.com") || host.equals("qyapi.weixin.qq.com")) {
                    return new InetAddress[]{InetAddress.getByAddress(host, new byte[]{(byte) 203, 0, (byte) 113, 7})};
                }
                throw new java.net.UnknownHostException(host);
            };
        }
    }

    static org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory lettuceFactory;

    @AfterAll
    static void down() {
        if (lettuceFactory != null) {
            lettuceFactory.stop();
            lettuceFactory.destroy();
        }
        REDIS.stop();
        PG.stop();
    }

    @Autowired MockMvc mvc;
    @Autowired NfyaTenantMapper tenantMapper;
    @Autowired NfyaChannelMapper channelMapper;

    private static final long TENANT_A = 89301L;
    private static final long TENANT_B = 89302L;

    @BeforeAll
    void init() throws Exception {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        for (long[] ids : new long[][]{{TENANT_A}, {TENANT_B}}) {
            NfyaTenant t = new NfyaTenant();
            t.setId(ids[0]);
            t.setName("T" + ids[0]);
            t.setStatus("ACTIVE");
            t.setTenantSecret("secret-" + ids[0]);
            tenantMapper.insert(t);
        }
    }

    private String token(long tenantId) throws Exception {
        String body = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=" + tenantId + "&client_secret=secret-" + tenantId))
                .andReturn().getResponse().getContentAsString();
        if (!body.contains("access_token")) {
            throw new IllegalStateException("换 token 失败: " + body);
        }
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    private String createType(String jwt, String code) throws Exception {
        return mvc.perform(post("/nfy/api/v1/admin/types")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json")
                        .content("{\"type_code\":\"" + code + "\",\"name\":\"t\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    private String send(String jwt, String typeCode, String bizNo, String userId, String title) throws Exception {
        String body = "{\"type_code\":\"" + typeCode + "\",\"user_ids\":[\"" + userId + "\"],"
                + "\"title\":\"" + title + "\",\"content\":\"c\",\"biz_no\":\"" + bizNo + "\"}";
        return mvc.perform(post("/nfy/api/v1/runtime/messages")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", userId)
                        .contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String extract(String body, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\":\"([^\"]+)\"").matcher(body);
        org.assertj.core.api.Assertions.assertThat(m.find()).as("字段 %s 存在: %s", field, body).isTrue();
        return m.group(1);
    }

    @Test
    void send_results_by_bizno_with_deliveries_and_10400() throws Exception {
        String jwt = token(TENANT_A);
        createType(jwt, "QR");
        // 造一个已验证渠道 → 发送产生投递行
        NfyaChannel ch = new NfyaChannel();
        ch.setTenantId(TENANT_A);
        ch.setScope("USER");
        ch.setUserid("qr_u");
        ch.setChannelType("DINGTALK");
        ch.setName("渠道");
        ch.setTarget("https://oapi.dingtalk.com/robot/send?access_token=qr1");
        ch.setSecret("");
        ch.setKeyword("");
        ch.setStatus("ENABLED");
        ch.setFailCount(0);
        ch.setExt("{}");
        channelMapper.insert(ch);

        String sent = send(jwt, "QR", "qr-biz-1", "qr_u", "发送结果查询");
        assertThatCode0(sent);
        String messageId = extract(sent, "message_id");

        // MSG-003：按 biz_no 查（T 鉴权无 U）
        String result = mvc.perform(get("/nfy/api/v1/runtime/send-results/qr-biz-1")
                        .header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThatCode0(result);
        org.assertj.core.api.Assertions.assertThat(result)
                .contains("\"message_id\":\"" + messageId + "\"")
                .contains("\"biz_no\":\"qr-biz-1\"")
                .contains("\"deliveries\":[");

        // 未知 biz_no → 10400
        expectCode(mvc.perform(get("/nfy/api/v1/runtime/send-results/nope-404")
                        .header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString(), 10400);
        // 跨租户 biz_no → 10400（防探测）
        String jwtB = token(TENANT_B);
        expectCode(mvc.perform(get("/nfy/api/v1/runtime/send-results/qr-biz-1")
                        .header("Authorization", "Bearer " + jwtB))
                .andReturn().getResponse().getContentAsString(), 10400);
    }

    @Test
    void message_detail_marks_read_and_rejects_others() throws Exception {
        String jwt = token(TENANT_A);
        createType(jwt, "QD");
        String sent = send(jwt, "QD", "qd-1", "qd_u", "详情消息");
        String messageId = extract(sent, "message_id");

        // 未读 1 → 详情（返回即置已读）→ 未读 0 且 read_status=READ
        mvc.perform(get("/nfy/api/v1/runtime/messages/unread-count")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "qd_u"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unread_count").value(1));
        String detail = mvc.perform(get("/nfy/api/v1/runtime/messages/" + messageId)
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "qd_u"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThatCode0(detail);
        org.assertj.core.api.Assertions.assertThat(detail).contains("\"read_status\":\"READ\"");
        mvc.perform(get("/nfy/api/v1/runtime/messages/unread-count")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "qd_u"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unread_count").value(0));

        // 其他用户访问 → 10400（防探测）
        expectCode(mvc.perform(get("/nfy/api/v1/runtime/messages/" + messageId)
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "someone_else"))
                .andReturn().getResponse().getContentAsString(), 10400);
    }

    @Test
    void recent_messages_limited_10_for_bell() throws Exception {
        String jwt = token(TENANT_A);
        createType(jwt, "QR2");
        for (int i = 1; i <= 12; i++) {
            assertThatCode0(send(jwt, "QR2", "recent-" + i, "recent_u", "最近" + i));
        }
        String recent = mvc.perform(get("/nfy/api/v1/runtime/messages/recent?limit=10")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "recent_u"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThatCode0(recent);
        org.assertj.core.api.Assertions.assertThat(recent).contains("最近12").doesNotContain("最近2\"");
    }

    @Test
    void dictionaries_deliver_all_enums() throws Exception {
        String jwt = token(TENANT_A);
        String dict = mvc.perform(get("/nfy/api/v1/runtime/dictionaries")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "qr_u"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThatCode0(dict);
        org.assertj.core.api.Assertions.assertThat(dict)
                .contains("NORMAL").contains("URGENT")
                .contains("DINGTALK").contains("EMAIL")
                .contains("UNREAD").contains("PENDING").contains("DEAD");
    }

    @Test
    void stats_overviews_for_tenant_and_platform() throws Exception {
        String jwt = token(TENANT_A);
        String tenantStats = mvc.perform(get("/nfy/api/v1/admin/stats/overview")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThatCode0(tenantStats);
        org.assertj.core.api.Assertions.assertThat(tenantStats)
                .contains("today_sent").contains("today_delivered")
                .contains("deliver_success_rate").contains("channel_count");

        // 平台概览（PLATFORM token）
        String plat = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=PLATFORM&client_secret=platform-secret-IT"))
                .andReturn().getResponse().getContentAsString();
        String platJwt = plat.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        String platformStats = mvc.perform(get("/nfy/platform/api/v1/stats/overview")
                        .header("Authorization", "Bearer " + platJwt))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThatCode0(platformStats);
        org.assertj.core.api.Assertions.assertThat(platformStats)
                .contains("tenant_count").contains("active_tenants")
                .contains("dead_count").contains("dead_tenants");
    }

    private void expectCode(String body, int code) {
        org.assertj.core.api.Assertions.assertThat(body).contains("\"code\":" + code);
    }

    private void assertThatCode0(String body) {
        org.assertj.core.api.Assertions.assertThat(body).contains("\"code\":0").doesNotContain("\"fail\":true");
    }
}
