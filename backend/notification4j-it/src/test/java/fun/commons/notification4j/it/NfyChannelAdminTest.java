package fun.commons.notification4j.it;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import fun.commons.notification4j.entity.NfyaDelivery;
import fun.commons.notification4j.entity.NfyaTenant;
import fun.commons.notification4j.mapper.NfyaDeliveryMapper;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 10 步：公共渠道管理面（API-ACH-001~003，§4.3/§5.9.2；TENANT admin 面）。
 * VECTOR: TAG=step10-ach
 * 契约（§5.9.2「同用户渠道，差异：body 无 userid（scope=TENANT）」）：
 * 注册落库 PENDING（SSRF 同构 10609）；同 target 重复 10401；验证=业务码成功 → ENABLED；
 * 公共渠道（scope=TENANT, userid=''）与用户渠道（scope=USER）隔离互不可见；
 * 全链联通：ACH 注册 → 验证 ENABLED → AAN 公告引用 → 外发计划产公共渠道投递行（6a 消费端）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfyChannelAdminTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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
@Tag("step10")
class NfyChannelAdminTest {

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
    @Autowired NfyaDeliveryMapper deliveryMapper;
    @Autowired
    fun.commons.notification4j.service.ChannelVerifier channelVerifier;

    private static final long TENANT_A = 88901L;
    private static final long TENANT_B = 88902L;

    @BeforeAll
    void init() throws Exception {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        insertTenant(TENANT_A, "A", "secret-a");
        insertTenant(TENANT_B, "B", "secret-b");
    }

    private void insertTenant(long id, String name, String secret) {
        NfyaTenant t = new NfyaTenant();
        t.setId(id);
        t.setName(name);
        t.setStatus("ACTIVE");
        t.setTenantSecret(secret);
        tenantMapper.insert(t);
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

    private String register(String jwt, String body) throws Exception {
        return mvc.perform(post("/nfy/api/v1/admin/channels")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String verify(String jwt, String id) throws Exception {
        return mvc.perform(post("/nfy/api/v1/admin/channels/" + id + "/verify")
                        .header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void register_public_channel_pending_and_scope_isolation() throws Exception {
        String jwtA = token(TENANT_A, "secret-a");
        String created = register(jwtA, "{\"channel_type\":\"DINGTALK\",\"name\":\"公共报警群\","
                + "\"target\":\"https://oapi.dingtalk.com/robot/send?access_token=pub-ach-1\",\"secret\":\"SECx\"}");
        assertThatCode0(created);
        org.assertj.core.api.Assertions.assertThat(created).contains("\"status\":\"PENDING\"");
        String id = extract(created, "channel_id");

        // admin 列表可见；runtime 我的渠道列表不可见（scope 隔离）
        String adminList = mvc.perform(get("/nfy/api/v1/admin/channels")
                        .header("Authorization", "Bearer " + jwtA))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThatCode0(adminList);
        org.assertj.core.api.Assertions.assertThat(adminList).contains("公共报警群");
        String runtimeList = mvc.perform(get("/nfy/api/v1/runtime/channels")
                        .header("Authorization", "Bearer " + jwtA).header("X-User-Id", "u_1"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(runtimeList).doesNotContain("公共报警群");

        // B 租户 admin 列表不可见（跨租户隔离）
        String jwtB = token(TENANT_B, "secret-b");
        String listB = mvc.perform(get("/nfy/api/v1/admin/channels")
                        .header("Authorization", "Bearer " + jwtB))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(listB).doesNotContain("公共报警群");

        // B 验证 A 的渠道 → 10400
        expectCode(verify(jwtB, id), 10400);
    }

    @Test
    void register_ssrf_duplicate_and_full_link_to_delivery() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        // SSRF 同构：http → 10609
        expectCode(register(jwt, "{\"channel_type\":\"DINGTALK\",\"name\":\"n1\","
                + "\"target\":\"http://oapi.dingtalk.com/x\"}"), 10609);

        // 注册+验证全链
        String created = register(jwt, "{\"channel_type\":\"DINGTALK\",\"name\":\"公告渠道\","
                + "\"target\":\"https://oapi.dingtalk.com/robot/send?access_token=pub-ach-2\",\"keyword\":\"通知\"}");
        assertThatCode0(created);
        String id = extract(created, "channel_id");

        // 同 target 重复 → 10401
        expectCode(register(jwt, "{\"channel_type\":\"DINGTALK\",\"name\":\"重复\","
                + "\"target\":\"https://oapi.dingtalk.com/robot/send?access_token=pub-ach-2\"}"), 10401);

        // 验证 → ENABLED
        org.springframework.test.web.client.MockRestServiceServer server =
                org.springframework.test.web.client.MockRestServiceServer.bindTo(channelVerifier.restTemplate()).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withSuccess("{\"errcode\":0}", org.springframework.http.MediaType.APPLICATION_JSON));
        String verified = verify(jwt, id);
        assertThatCode0(verified);
        org.assertj.core.api.Assertions.assertThat(verified).contains("\"status\":\"ENABLED\"");
        server.verify();

        // AAN 公告引用公共渠道 → publish → 外发计划产 1 行公共渠道投递（userid=''）
        long eff = System.currentTimeMillis() - 1000;
        long exp = System.currentTimeMillis() + 86400000L;
        String ann = mvc.perform(post("/nfy/api/v1/admin/announcements")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json")
                        .content("{\"title\":\"ACH联通\",\"content\":\"c\",\"effective_at\":" + eff
                                + ",\"expire_at\":" + exp + ",\"channel_ids\":[\"" + id + "\"],\"biz_no\":\"ach-ann-1\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThatCode0(ann);
        String annId = extract(ann, "announcement_id");
        String published = mvc.perform(post("/nfy/api/v1/admin/announcements/" + annId + "/publish")
                        .header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString();
        assertThatCode0(published);
        org.assertj.core.api.Assertions.assertThat(published).contains("\"delivery_planned\":1");

        Long pubRows = deliveryMapper.selectCount(new LambdaQueryWrapper<NfyaDelivery>()
                .eq(NfyaDelivery::getTenantId, TENANT_A)
                .eq(NfyaDelivery::getSourceType, "ANNOUNCEMENT")
                .eq(NfyaDelivery::getUserid, ""));
        org.assertj.core.api.Assertions.assertThat(pubRows).isEqualTo(1);
    }

    private void expectCode(String body, int code) {
        org.assertj.core.api.Assertions.assertThat(body).contains("\"code\":" + code);
    }

    private String extract(String body, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\":\"([^\"]+)\"").matcher(body);
        org.assertj.core.api.Assertions.assertThat(m.find()).as("字段 %s 存在: %s", field, body).isTrue();
        return m.group(1);
    }

    private void assertThatCode0(String body) {
        org.assertj.core.api.Assertions.assertThat(body).contains("\"code\":0").doesNotContain("\"fail\":true");
    }
}
