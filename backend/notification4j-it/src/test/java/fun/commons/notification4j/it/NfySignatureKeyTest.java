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
 * 第 11 步：签名密钥管理（API-SEC-001，§4.3/§5.9.2；TENANT admin 面）。
 * VECTOR: TAG=step11-sec
 * 契约：GET 查询密钥状态（脱敏输出，禁回显完整明文）；POST 注册或轮换 {secret}
 * （旧密钥进 prev + prev_at 宽限 24h，§5.5）；轮换后新密钥立即用于换 token（认证链路生效）。
 * tenant_secret 双用途：client_credentials 认证凭据 + HMAC-SHA256 签名密钥
 * （NfyTenantSecretProvider 读解密列供 fwk4j-signature）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfySignatureKeyTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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
@Tag("step11")
class NfySignatureKeyTest {

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

    private static final long TENANT = 89101L;
    private static final String ORIGINAL_SECRET = "orig-secret-0123456789abcdef";

    @BeforeAll
    void init() throws Exception {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        NfyaTenant t = new NfyaTenant();
        t.setId(TENANT);
        t.setName("A");
        t.setStatus("ACTIVE");
        t.setTenantSecret(ORIGINAL_SECRET);
        tenantMapper.insert(t);
    }

    private String token(String secret) throws Exception {
        String body = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=" + TENANT + "&client_secret=" + secret))
                .andReturn().getResponse().getContentAsString();
        if (!body.contains("access_token")) {
            throw new IllegalStateException("换 token 失败: " + body);
        }
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    private String getStatus(String jwt) throws Exception {
        return mvc.perform(get("/nfy/api/v1/admin/signature-key").header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String rotate(String jwt, String secret) throws Exception {
        return mvc.perform(post("/nfy/api/v1/admin/signature-key")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json")
                        .content("{\"secret\":\"" + secret + "\"}"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void rotate_flow_new_secret_immediately_valid() throws Exception {
        String jwt = token(ORIGINAL_SECRET);

        // GET 初始状态：已注册 + 脱敏（不出现完整明文）
        String before = getStatus(jwt);
        assertThatCode0(before);
        org.assertj.core.api.Assertions.assertThat(before)
                .contains("\"configured\":true")
                .doesNotContain(ORIGINAL_SECRET);

        // 轮换 → 新 secret 立即可换 token（认证链路生效）；旧 secret 失效（默认无宽限放行口径）
        String newSecret = "rotated-secret-fedcba9876543210";
        String rotated = rotate(jwt, newSecret);
        assertThatCode0(rotated);
        org.assertj.core.api.Assertions.assertThat(rotated).contains("\"rotated\":true");

        // GET 状态：prev 存在（宽限中）+ 新密钥脱敏
        String after = getStatus(jwt);
        org.assertj.core.api.Assertions.assertThat(after)
                .contains("\"has_prev\":true")
                .doesNotContain(newSecret)
                .doesNotContain(ORIGINAL_SECRET);

        // 新 secret 换 token 生效（跨过轮换立刻验证）
        String newJwt = token(newSecret);
        org.assertj.core.api.Assertions.assertThat(newJwt).isNotBlank();
    }

    @Test
    void rotate_validation_empty_too_short() throws Exception {
        String jwt = token(ORIGINAL_SECRET);
        // 空 secret → 10100
        expectCode(rotates(jwt, ""), 10100);
        // 过短（<16）→ 10100
        expectCode(rotates(jwt, "short"), 10100);
    }

    private String rotates(String jwt, String secret) throws Exception {
        return mvc.perform(post("/nfy/api/v1/admin/signature-key")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json")
                        .content("{\"secret\":\"" + secret + "\"}"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void expectCode(String body, int code) {
        org.assertj.core.api.Assertions.assertThat(body).contains("\"code\":" + code);
    }

    private void assertThatCode0(String body) {
        org.assertj.core.api.Assertions.assertThat(body).contains("\"code\":0").doesNotContain("\"fail\":true");
    }
}
