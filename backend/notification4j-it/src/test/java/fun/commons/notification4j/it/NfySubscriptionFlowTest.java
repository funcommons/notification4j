package fun.commons.notification4j.it;

import fun.commons.notification4j.entity.NfyaMessageType;
import fun.commons.notification4j.entity.NfyaTenant;
import fun.commons.notification4j.mapper.NfyaMessageTypeMapper;
import fun.commons.notification4j.mapper.NfyaSubscriptionMapper;
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
 * 第 4b 步：订阅矩阵（API-SUB-001/002，§5.8）。
 * VECTOR: TAG=step4-subscription
 * 契约：GET 三段（types 启用类型 / available_channels ENABLED+INAPP 哨兵 / items 已配置）；
 * PUT 全量替换 last-write-wins；空 channel_ids → 10101；channel_id 须属本人且 ENABLED → 10400；
 * 强制集校验按「类型语义→实例」：mandatory 类型 default_channels 中每渠道类型，
 * 用户有该类型 ENABLED 实例则须保留 ≥1（关最后实例 → 10606），无实例豁免（INAPP 锁定兜底）；
 * INAPP 哨兵服务端补齐；删除渠道级联剔除订阅中的失效 id（回落 INAPP）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfySubscriptionFlowTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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
                "framework4j.access-token.exclude-path-patterns=/nfy/api/v1/auth/token",
                "framework4j.access-token.policies.TENANT.key=tenant_id",
                "framework4j.access-token.policies.TENANT.expire-time=28800",
                "framework4j.access-token.policies.PLATFORM.key=tenant_id",
                "framework4j.access-token.policies.PLATFORM.expire-time=28800",
                "framework4j.sensitive.enabled=true",
                "framework4j.sensitive.encryption-key=12345678901234567890123456789012"
        })
@AutoConfigureMockMvc
@Tag("step4")
class NfySubscriptionFlowTest {

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
    @Autowired NfyaMessageTypeMapper typeMapper;
    @Autowired NfyaSubscriptionMapper subscriptionMapper;
    @Autowired
    fun.commons.notification4j.service.ChannelVerifier channelVerifier;

    private static final long TENANT_A = 88201L;
    private static final long TENANT_B = 88202L;

    @BeforeAll
    void init() throws Exception {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        insertTenant(TENANT_A, "A", "ACTIVE", "secret-a");
        insertTenant(TENANT_B, "B", "ACTIVE", "secret-b");
    }

    private void insertTenant(long id, String name, String status, String secret) {
        NfyaTenant t = new NfyaTenant();
        t.setId(id);
        t.setName(name);
        t.setStatus(status);
        t.setTenantSecret(secret);
        tenantMapper.insert(t);
    }

    /** 直插类型（mandatory 仅平台域可设，绕过 admin API 构造强制类型场景） */
    private void insertType(long tenantId, String code, String defaultChannels, int mandatory) {
        NfyaMessageType t = new NfyaMessageType();
        t.setTenantId(tenantId);
        t.setTypeCode(code);
        t.setName("类型-" + code);
        t.setDefaultLevel("NORMAL");
        t.setDefaultChannels(defaultChannels);
        t.setMandatory(mandatory);
        t.setBuiltIn(0);
        t.setStatus("ENABLED");
        t.setExt("{}");
        typeMapper.insert(t);
    }

    private String token(long tenantId, String secret) throws Exception {
        String body = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=" + tenantId + "&client_secret=" + secret))
                .andReturn().getResponse().getContentAsString();
        if (!body.contains("access_token")) {
            throw new IllegalStateException("换 token 失败: " + body);
        }
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    /** 注册并验证一个钉钉渠道（MockRestServiceServer 应答 errcode=0），返回 channel_id */
    private String enabledDingChannel(String jwt, String userid, String token) throws Exception {
        String body = mvc.perform(post("/nfy/api/v1/runtime/channels")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", userid)
                        .contentType("application/json")
                        .content("{\"channel_type\":\"DINGTALK\",\"name\":\"渠道-" + token + "\","
                                + "\"target\":\"https://oapi.dingtalk.com/robot/send?access_token=" + token + "\"}"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"channel_id\":\"([^\"]+)\"").matcher(body);
        org.assertj.core.api.Assertions.assertThat(m.find()).as("注册成功: %s", body).isTrue();
        String channelId = m.group(1);
        org.springframework.test.web.client.MockRestServiceServer server =
                org.springframework.test.web.client.MockRestServiceServer.bindTo(channelVerifier.restTemplate()).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withSuccess("{\"errcode\":0}", org.springframework.http.MediaType.APPLICATION_JSON));
        String verified = mvc.perform(post("/nfy/api/v1/runtime/channels/" + channelId + "/verify")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", userid))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(verified).contains("\"ENABLED\"");
        server.verify();
        return channelId;
    }

    private String getSubscriptions(String jwt, String userid) throws Exception {
        return mvc.perform(get("/nfy/api/v1/runtime/subscriptions")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", userid))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String putSubscriptions(String jwt, String userid, String itemsJson) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/nfy/api/v1/runtime/subscriptions")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", userid)
                        .contentType("application/json")
                        .content("{\"items\":" + itemsJson + "}"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void get_matrix_returns_types_and_available_channels() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        insertType(TENANT_A, "ORDER", "[\"INAPP\"]", 0);
        insertType(TENANT_A, "SECURITY", "[\"INAPP\",\"DINGTALK\"]", 1);
        String channelId = enabledDingChannel(jwt, "u_get", "sub-get-1");

        String body = getSubscriptions(jwt, "u_get");
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("\"type_code\":\"ORDER\"").contains("\"type_code\":\"SECURITY\"")
                .contains("\"mandatory\":1")
                .contains("\"channel_id\":\"INAPP\"")
                .contains(channelId);
        // items 为空（未配置）
        org.assertj.core.api.Assertions.assertThat(body).contains("\"items\":[]");
    }

    @Test
    void put_roundtrip_idempotent_and_inapp_sentinel() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        insertType(TENANT_A, "PAY", "[\"INAPP\"]", 0);
        String channelId = enabledDingChannel(jwt, "u_put", "sub-put-1");

        // 仅提交渠道实例 → INAPP 哨兵补齐
        String saved = putSubscriptions(jwt, "u_put",
                "[{\"type_code\":\"PAY\",\"channel_ids\":[\"" + channelId + "\"]}]");
        assertThatCode0(saved);
        org.assertj.core.api.Assertions.assertThat(saved).contains("\"saved_count\":1");

        String body = getSubscriptions(jwt, "u_put");
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("\"type_code\":\"PAY\"").contains("\"channel_ids\":[\"INAPP\",\"" + channelId + "\"]");

        // 重复 PUT 同内容 → 幂等（仍 1 行）
        putSubscriptions(jwt, "u_put", "[{\"type_code\":\"PAY\",\"channel_ids\":[\"INAPP\",\"" + channelId + "\"]}]");
        Long rows = subscriptionRowCount(TENANT_A, "u_put");
        org.assertj.core.api.Assertions.assertThat(rows).isEqualTo(1);
    }

    @Test
    void put_empty_channel_ids_10101_unknown_type_10601() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        insertType(TENANT_A, "RP", "[\"INAPP\"]", 0);
        expectCode(putSubscriptions(jwt, "u_ep", "[{\"type_code\":\"RP\",\"channel_ids\":[]}]"), 10101);
        expectCode(putSubscriptions(jwt, "u_ep", "[{\"type_code\":\"GHOST\",\"channel_ids\":[\"INAPP\"]}]"), 10601);
    }

    @Test
    void mandatory_close_last_instance_10606_and_exempt_without_channel() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        insertType(TENANT_A, "SEC", "[\"INAPP\",\"DINGTALK\"]", 1);
        String channelId = enabledDingChannel(jwt, "u_man", "sub-man-1");

        // 用户有 ENABLED 钉钉渠道，强制类型关闭最后一个钉钉实例 → 10606
        expectCode(putSubscriptions(jwt, "u_man", "[{\"type_code\":\"SEC\",\"channel_ids\":[\"INAPP\"]}]"), 10606);
        // 保留实例 → 通过
        assertThatCode0(putSubscriptions(jwt, "u_man",
                "[{\"type_code\":\"SEC\",\"channel_ids\":[\"INAPP\",\"" + channelId + "\"]}]"));

        // 其他用户无钉钉渠道 → 豁免，仅 INAPP 合法
        String other = mvc.perform(putSubscriptionsRequest(jwt, "u_exempt",
                "[{\"type_code\":\"SEC\",\"channel_ids\":[\"INAPP\"]}]")).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThatCode0(other);
    }

    @Test
    void put_foreign_pending_or_missing_channel_10400() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        insertType(TENANT_A, "FW", "[\"INAPP\"]", 0);
        // 未注册渠道 id → 10400
        expectCode(putSubscriptions(jwt, "u_fw",
                "[{\"type_code\":\"FW\",\"channel_ids\":[\"INAPP\",\"999999\"]}]"), 10400);

        // PENDING 渠道（未验证）→ 10400
        String pending = mvc.perform(post("/nfy/api/v1/runtime/channels")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_fw")
                        .contentType("application/json")
                        .content("{\"channel_type\":\"WECOM\",\"name\":\"未验证\","
                                + "\"target\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=p1\"}"))
                .andReturn().getResponse().getContentAsString();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"channel_id\":\"([^\"]+)\"").matcher(pending);
        org.assertj.core.api.Assertions.assertThat(m.find()).isTrue();
        expectCode(putSubscriptions(jwt, "u_fw",
                "[{\"type_code\":\"FW\",\"channel_ids\":[\"INAPP\",\"" + m.group(1) + "\"]}]"), 10400);

        // 跨租户他人渠道 → 10400
        String jwtB = token(TENANT_B, "secret-b");
        String foreign = enabledDingChannel(jwtB, "u_fb", "sub-foreign");
        expectCode(putSubscriptions(jwt, "u_fw",
                "[{\"type_code\":\"FW\",\"channel_ids\":[\"INAPP\",\"" + foreign + "\"]}]"), 10400);
    }

    @Test
    void full_replace_deletes_unlisted_types() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        insertType(TENANT_A, "FR1", "[\"INAPP\"]", 0);
        insertType(TENANT_A, "FR2", "[\"INAPP\"]", 0);
        assertThatCode0(putSubscriptions(jwt, "u_fr",
                "[{\"type_code\":\"FR1\",\"channel_ids\":[\"INAPP\"]},{\"type_code\":\"FR2\",\"channel_ids\":[\"INAPP\"]}]"));
        org.assertj.core.api.Assertions.assertThat(subscriptionRowCount(TENANT_A, "u_fr")).isEqualTo(2);

        // 全量替换：只提交 FR1 → FR2 行删除（FR2 仍在 types 段，items 段仅 FR1）
        assertThatCode0(putSubscriptions(jwt, "u_fr",
                "[{\"type_code\":\"FR1\",\"channel_ids\":[\"INAPP\"]}]"));
        org.assertj.core.api.Assertions.assertThat(subscriptionRowCount(TENANT_A, "u_fr")).isEqualTo(1);
        String body = getSubscriptions(jwt, "u_fr");
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("\"items\":[{\"type_code\":\"FR1\",\"channel_ids\":[\"INAPP\"]}]");
    }

    @Test
    void delete_channel_cascades_subscription_back_to_inapp() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        insertType(TENANT_A, "DC", "[\"INAPP\"]", 0);
        String channelId = enabledDingChannel(jwt, "u_del", "sub-del-1");
        assertThatCode0(putSubscriptions(jwt, "u_del",
                "[{\"type_code\":\"DC\",\"channel_ids\":[\"INAPP\",\"" + channelId + "\"]}]"));

        // 删除渠道 → 订阅剔除失效 id，回落 ["INAPP"]
        assertThatCode0(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/nfy/api/v1/runtime/channels/" + channelId)
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_del"))
                .andReturn().getResponse().getContentAsString());
        String body = getSubscriptions(jwt, "u_del");
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("\"type_code\":\"DC\"")
                .doesNotContain("\"channel_ids\":[\"INAPP\",\"" + channelId + "\"]");
        org.assertj.core.api.Assertions.assertThat(body).contains("\"channel_ids\":[\"INAPP\"]");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putSubscriptionsRequest(
            String jwt, String userid, String itemsJson) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/nfy/api/v1/runtime/subscriptions")
                .header("Authorization", "Bearer " + jwt).header("X-User-Id", userid)
                .contentType("application/json")
                .content("{\"items\":" + itemsJson + "}");
    }

    private Long subscriptionRowCount(long tenantId, String userid) {
        return subscriptionMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<fun.commons.notification4j.entity.NfyaSubscription>()
                .eq(fun.commons.notification4j.entity.NfyaSubscription::getTenantId, tenantId)
                .eq(fun.commons.notification4j.entity.NfyaSubscription::getUserid, userid));
    }

    private void expectCode(String body, int code) {
        org.assertj.core.api.Assertions.assertThat(body).contains("\"code\":" + code);
    }

    private void assertThatCode0(String body) {
        org.assertj.core.api.Assertions.assertThat(body).contains("\"code\":0").doesNotContain("\"fail\":true");
    }

    private void assertThatCode0(String body, int ignored) {
        assertThatCode0(body);
    }
}
