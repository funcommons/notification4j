package fun.commons.notification4j.it;

import fun.commons.framework4j.signature.util.BodyMd5Util;
import fun.commons.framework4j.signature.util.SignatureUtil;
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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * D-1/D-2 签名面活体验证（TDD 先红后绿）。
 * VECTOR: TAG=sigface
 *
 * <p>用例1 钉 D-2：fwk4j-signature SignatureAutoConfiguration（FQCN
 * fun.commons.framework4j.signature.config.SignatureAutoConfiguration）的 InMemorySecretProvider
 * 兜底 @Bean 与本项目 NfyTenantSecretProvider 无处理顺序约束（类名字典序 fwk4j 先行）→
 * 本项目 provider 让位 → 平台建租户后按产品客户端口径签名（SignatureUtil，STS=METHOD\nPATH\nts\nnonce\nbodyMD5，
 * HMAC-SHA256 with tenant_secret，X-Access-Key=open_id）调 runtime 面仍 10200（未知的 AccessKey）。
 * 修复后（@AutoConfigureBefore 排序 + 删本项目侧 @ConditionalOnMissingBean）须 code=0。
 *
 * <p>用例2 钉面生效：path-patterns 命中 /nfy/api/v1/runtime/** 且不带签名头 → 10101
 * （证明拦截器真在拦截，patterns 非空生效）。
 *
 * <p>Redis 链路：signature 模块经 MultiRedisManager.getStringRedisTemplate("default") 解析，
 * 无 datasources.default 注册时降级查容器 stringRedisTemplate Bean（TestApp 已定义，同既有 IT）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfySignatureFaceTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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
                "framework4j.sensitive.encryption-key=12345678901234567890123456789012",
                "framework4j.signature.enabled=true",
                "framework4j.signature.path-patterns[0]=/nfy/api/v1/runtime/**",
                "framework4j.signature.timestamp-tolerance-ms=300000",
                "framework4j.signature.nonce-ttl-seconds=600"
        })
@AutoConfigureMockMvc
@Tag("sigface")
class NfySignatureFaceTest {

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

    private String openId;
    private String tenantSecret;

    @BeforeAll
    void init() throws Exception {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        // 平台建租户 → {open_id, tenant_secret} 明文一次（PTE-001）
        String plat = platformToken();
        String created = mvc.perform(post("/nfy/platform/api/v1/tenants")
                        .header("Authorization", "Bearer " + plat)
                        .contentType("application/json")
                        .content("{\"name\":\"sigface\",\"email\":\"sigface@test.local\"}"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(created).contains("\"code\":0")
                .contains("open_id").contains("tenant_secret");
        openId = extract(created, "open_id");
        tenantSecret = extract(created, "tenant_secret");
    }

    private String platformToken() throws Exception {
        String body = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=PLATFORM&client_secret=platform-secret-IT"))
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    private String tenantToken() throws Exception {
        String body = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=" + openId + "&client_secret=" + tenantSecret))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).contains("access_token");
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    /** 产品客户端口径：GET 空 body，STS=GET\nPATH\nts\nnonce\n空bodyMD5，HMAC-SHA256Base64(tenant_secret) */
    private String[] signGet(String path) {
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String sts = SignatureUtil.buildStringToSign("GET", path, ts, nonce, BodyMd5Util.md5Hex(new byte[0]));
        return new String[]{ts, nonce, SignatureUtil.sign(tenantSecret, sts)};
    }

    /** 用例1（钉 D-2）：合法签名 + 合法 TENANT token → code=0（修复前 InMemorySecretProvider 兜底致 10200） */
    @Test
    void signed_runtime_request_with_tenant_secret_passes() throws Exception {
        String jwt = tenantToken();
        String[] s = signGet("/nfy/api/v1/runtime/messages");
        String body = mvc.perform(get("/nfy/api/v1/runtime/messages")
                        .header("Authorization", "Bearer " + jwt)
                        .header("X-User-Id", "sigface-u1")
                        .header("X-Access-Key", openId)
                        .header("X-Timestamp", s[0])
                        .header("X-Nonce", s[1])
                        .header("X-Signature", s[2]))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("\"code\":0")
                .doesNotContain("\"code\":10200");
    }

    /** 用例2（钉面生效）：同一端点带合法 token 但不带任何签名头 → 10101（patterns 命中且拦截器在工作） */
    @Test
    void unsigned_runtime_request_rejected_10101() throws Exception {
        String jwt = tenantToken();
        String body = mvc.perform(get("/nfy/api/v1/runtime/messages")
                        .header("Authorization", "Bearer " + jwt)
                        .header("X-User-Id", "sigface-u1"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(body).contains("\"code\":10101");
    }

    private static String extract(String json, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
        org.assertj.core.api.Assertions.assertThat(m.find()).as("字段 %s 存在: %s", field, json).isTrue();
        return m.group(1);
    }
}
