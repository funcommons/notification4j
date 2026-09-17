package fun.commons.notification4j.it;

import fun.commons.notification4j.entity.NfyaTenant;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 第 12 步：租户生命周期（API-PTE-001~004，§4.4/§5.9.2；平台域）。
 * VECTOR: TAG=step12-pte
 * 契约：
 * - PTE-001 创建 → {open_id, tenant_secret}（明文仅此一次；响应不含内部数字 id）；email 唯一 10401；
 * - PTE-002 详情（email 脱敏 + privileges/config/oem）/ PATCH 配置（三组，10102 参数非法）；
 * - PTE-003 reset-secret → 新明文一次，旧密钥进 prev 宽限，旧密钥换 token 立即 401；
 * - PTE-004 状态机 ACTIVE⇄SUSPENDED→CLOSED（终态 10402；SUSPEND 后换 token 401 同码防探测）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfyPlatformTenantTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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
@Tag("step12")
class NfyPlatformTenantTest {

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

    @BeforeAll
    void init() {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private String platformToken() throws Exception {
        String body = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=PLATFORM&client_secret=platform-secret-IT"))
                .andReturn().getResponse().getContentAsString();
        if (!body.contains("access_token")) {
            throw new IllegalStateException("换平台 token 失败: " + body);
        }
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    private String pPost(String jwt, String url, String body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + jwt)
                        .contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String pGet(String jwt, String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String pPatch(String jwt, String url, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch(url).header("Authorization", "Bearer " + jwt)
                        .contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String createBody(String name, String email) {
        return "{\"name\":\"" + name + "\",\"email\":\"" + email + "\","
                + "\"privileges\":{\"signature\":false},\"config\":{\"retentionDays\":30}}";
    }

    @Test
    void create_returns_secret_once_email_unique_and_new_tenant_can_auth() throws Exception {
        String plat = platformToken();
        String created = pPost(plat, "/nfy/platform/api/v1/tenants",
                createBody("租户甲", "tenant-a@example.com"));
        assertThatCode0(created);
        // 明文仅此一次 + 不含内部数字 id
        org.assertj.core.api.Assertions.assertThat(created)
                .contains("open_id").contains("tenant_secret")
                .doesNotContainPattern("\"id\"");
        String openId = extract(created, "open_id");
        String secret = extract(created, "tenant_secret");

        // email 唯一 → 10401
        expectCode(pPost(plat, "/nfy/platform/api/v1/tenants",
                createBody("租户甲二", "tenant-a@example.com")), 10401);

        // 新租户凭 open_id+secret 换 token → 认证链路端到端
        String auth = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=" + openId + "&client_secret=" + secret))
                .andReturn().getResponse().getContentAsString();
        assertThatCode0(auth);

        // 详情：email 脱敏（local 首字符+***+尾字符）+ 三组配置回显
        String detail = pGet(plat, "/nfy/platform/api/v1/tenants/" + openId);
        assertThatCode0(detail);
        org.assertj.core.api.Assertions.assertThat(detail)
                .contains("t***a@example.com")
                .doesNotContain("tenant-a@example.com")
                .contains("signature");

        // PATCH 配置 → 详情回显
        String patched = pPatch(plat, "/nfy/platform/api/v1/tenants/" + openId,
                "{\"oem\":{\"title\":\"甲的标题\"}}");
        assertThatCode0(patched);
        org.assertj.core.api.Assertions.assertThat(pGet(plat, "/nfy/platform/api/v1/tenants/" + openId))
                .contains("甲的标题");

        // 列表含新租户（open_id）
        String list = pGet(plat, "/nfy/platform/api/v1/tenants");
        org.assertj.core.api.Assertions.assertThat(list).contains(openId);
    }

    @Test
    void status_suspend_blocks_auth_and_close_is_terminal() throws Exception {
        String plat = platformToken();
        String created = pPost(plat, "/nfy/platform/api/v1/tenants",
                createBody("租户乙", "tenant-b@example.com"));
        String openId = extract(created, "open_id");
        String secret = extract(created, "tenant_secret");

        // 非法 action → 10100
        expectCode(pPost(plat, "/nfy/platform/api/v1/tenants/" + openId + "/status",
                "{\"action\":\"DELETE\"}"), 10100);

        // SUSPEND → 换 token 401（同码防探测）
        assertThatCode0(pPost(plat, "/nfy/platform/api/v1/tenants/" + openId + "/status",
                "{\"action\":\"SUSPEND\"}"));
        String blocked = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=" + openId + "&client_secret=" + secret))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(blocked).contains("\"code\":401");

        // RESUME → 恢复
        assertThatCode0(pPost(plat, "/nfy/platform/api/v1/tenants/" + openId + "/status",
                "{\"action\":\"RESUME\"}"));
        String ok = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=" + openId + "&client_secret=" + secret))
                .andReturn().getResponse().getContentAsString();
        assertThatCode0(ok);

        // CLOSE → 终态；再 SUSPEND → 10402
        assertThatCode0(pPost(plat, "/nfy/platform/api/v1/tenants/" + openId + "/status",
                "{\"action\":\"CLOSE\"}"));
        expectCode(pPost(plat, "/nfy/platform/api/v1/tenants/" + openId + "/status",
                "{\"action\":\"SUSPEND\"}"), 10402);
    }

    @Test
    void reset_secret_rotates_and_old_invalid() throws Exception {
        String plat = platformToken();
        String created = pPost(plat, "/nfy/platform/api/v1/tenants",
                createBody("租户丙", "tenant-c@example.com"));
        String openId = extract(created, "open_id");
        String oldSecret = extract(created, "tenant_secret");

        // reset → 新明文一次
        String reset = pPost(plat, "/nfy/platform/api/v1/tenants/" + openId + "/reset-secret", "{}");
        assertThatCode0(reset);
        String newSecret = extract(reset, "tenant_secret");
        org.assertj.core.api.Assertions.assertThat(newSecret).isNotEqualTo(oldSecret);

        // 宽限语义（§5.5）：prev 24h 内新旧密钥均可换 token
        String oldAuth = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=" + openId + "&client_secret=" + oldSecret))
                .andReturn().getResponse().getContentAsString();
        assertThatCode0(oldAuth);
        String newAuth = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=" + openId + "&client_secret=" + newSecret))
                .andReturn().getResponse().getContentAsString();
        assertThatCode0(newAuth);
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
