package fun.commons.notification4j.it;

import com.baomidou.mybatisplus.annotation.TableName;
import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.PlatformDomain;
import fun.commons.framework4j.tenant.annotation.TenantDomain;
import fun.commons.framework4j.tenant.entity.TenantEntity;
import fun.commons.notification4j.entity.NfyaTenant;
import fun.commons.notification4j.mapper.NfyaTenantMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 2 步：认证闭环（复用 framework4j-tenant TenantAuthEndpoint/防爆破/合成平台租户 + DomainGuard 型别隔离）。
 * VECTOR: TAG=step2-auth
 * 契约：client_credentials 换 token；凭据错误非 0 码；停用租户拒发；连续失败锁定；平台/租户型别互打隔离。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfyAuthFlowTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.application.name=notification4j-it",
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
                "framework4j.access-token.policies.TENANT.key=tenant_id",
                "framework4j.access-token.policies.TENANT.expire-time=28800",
                "framework4j.access-token.policies.PLATFORM.key=tenant_id",
                "framework4j.access-token.policies.PLATFORM.expire-time=28800",
                "framework4j.sensitive.enabled=true",
                "framework4j.sensitive.encryption-key=12345678901234567890123456789012",
                "logging.level.fun.commons.framework4j=DEBUG"
        })
@AutoConfigureMockMvc
@Tag("step2")
class NfyAuthFlowTest {

    static org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory lettuceFactory;

    @org.junit.jupiter.api.AfterAll
    static void down() {
        // 销毁 lettuce(非守护线程) + 停容器, 避免 surefire fork JVM 挂起不退出
        if (lettuceFactory != null) {
            lettuceFactory.stop();
            lettuceFactory.destroy();
        }
        REDIS.stop();
        PG.stop();
    }

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
            ds.setUrl(PG.getJdbcUrl());
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

        // MultiRedisManager 按 bean 名兜底解析 "default" → stringRedisTemplate(fwk4j 源码逻辑)
        @Bean
        org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate(
                org.springframework.data.redis.connection.RedisConnectionFactory factory) {
            return new org.springframework.data.redis.core.StringRedisTemplate(factory);
        }

        // 型别隔离探针：10207 判定用（@TenantDomain/@PlatformDomain 为类型级守卫）
        @TenantDomain
        @RequiresToken(value = "TENANT", type = "access")
        @org.springframework.web.bind.annotation.RestController
        static class TenantProbe {
            @org.springframework.web.bind.annotation.GetMapping("/it/probe/tenant")
            public String probe() {
                return "tenant-ok:" + fun.commons.framework4j.tenant.context.UserIdContext.currentUserId();
            }
        }

        @PlatformDomain
        @org.springframework.web.bind.annotation.RestController
        static class PlatformProbe {
            @org.springframework.web.bind.annotation.GetMapping("/it/probe/platform")
            public String probe() {
                return "platform-ok";
            }
        }
    }

    @Autowired MockMvc mvc;
    @Autowired NfyaTenantMapper tenantMapper;

    private static final long TENANT_ID = 99001L;

    @BeforeAll
    void init() {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        NfyaTenant t = new NfyaTenant();
        t.setId(TENANT_ID);
        t.setName("IT 租户");
        t.setEmail("it@nfy.test");
        t.setChannel("OPS");
        t.setStatus("ACTIVE");
        t.setTenantSecret("tenant-secret-IT"); // EncryptedFieldTypeHandler 写入自动加密
        tenantMapper.insert(t);
    }

    private static String form(String clientId, String secret) {
        return "grant_type=client_credentials&client_id=" + clientId + "&client_secret=" + secret;
    }

    private String token(long tenantId, String secret) throws Exception {
        String body = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content(form(String.valueOf(tenantId), secret)))
                .andReturn().getResponse().getContentAsString();
        if (!body.contains("access_token")) {
            throw new IllegalStateException("换 token 失败: " + body);
        }
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void tenant_exchanges_token_with_8h_expiry() throws Exception {
        String body = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content(form(String.valueOf(TENANT_ID), "tenant-secret-IT")))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).contains("\"code\":0");
        assertThat(body).contains("access_token").contains("token_type");
        // 契约默认 8h（API 设计文档 §2.3），允许 ±5s
        assertThat(body).contains("\"expires_in\":\"28800\"");
    }

    @Test
    void wrong_secret_rejected_non_zero() throws Exception {
        mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content(form(String.valueOf(TENANT_ID), "wrong-secret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isNumber())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    @Test
    void disabled_tenant_rejected() throws Exception {
        NfyaTenant t = new NfyaTenant();
        t.setId(99002L);
        t.setName("停用租户");
        t.setStatus("SUSPEND");
        t.setTenantSecret("secret-99002");
        tenantMapper.insert(t);

        mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content(form("99002", "secret-99002")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isNumber())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    @Test
    void platform_client_exchanges_token() throws Exception {
        String body = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content(form("PLATFORM", "platform-secret-IT")))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).contains("\"code\":0").contains("access_token");
    }

    @Test
    void brute_force_locks_even_with_correct_secret() throws Exception {
        // 先造真实租户(有正确密钥)
        NfyaTenant t = new NfyaTenant();
        t.setId(99101L);
        t.setName("被锁租户");
        t.setStatus("ACTIVE");
        t.setTenantSecret("secret-99101");
        tenantMapper.insert(t);

        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/nfy/api/v1/auth/token")
                    .contentType("application/x-www-form-urlencoded")
                    .content(form("99101", "bad"))).andExpect(status().isOk());
        }
        // 第 6 次即使密钥正确也必须被锁(框架锁定码 429, 非信封 0)
        mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content(form("99101", "secret-99101")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(429));
    }

    @Test
    void brute_force_lock_is_per_client_id() throws Exception {
        // 锁定维度 = client_id: 99101 被锁不影响其他租户正常换 token
        mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content(form(String.valueOf(TENANT_ID), "tenant-secret-IT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void x_user_id_header_propagates_via_interceptor() throws Exception {
        String jwt = token(TENANT_ID, "tenant-secret-IT");
        // 带 X-User-Id → 探针回显透传值（UserIdContext 拦截器自动注册）
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/it/probe/tenant")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_probe"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(body).contains("tenant-ok:u_probe");
    }

    @Test
    void tenant_token_on_platform_domain_rejected() throws Exception {
        String token = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content(form(String.valueOf(TENANT_ID), "tenant-secret-IT")))
                .andReturn().getResponse().getContentAsString();
        String jwt = token.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");

        // TENANT token 打平台域 → DomainGuard HTTP 403 + 信封 code=403（评审第 2 步 P2 实测钉死）
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/it/probe/platform").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }
}
