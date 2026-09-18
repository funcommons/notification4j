package fun.commons.notification4j.it;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import fun.commons.notification4j.entity.NfyaTenant;
import fun.commons.notification4j.mapper.NfyaTenantMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * API-OEM-001 本租户 oem.hosts 下发（V1.3，GitHub issue #1 补实现；L8 嵌入线）。
 * 契约：
 * - T 鉴权无 U（同 DICT-001）；oem.hosts 回显，空 oem/hosts 非数组/损坏 → []（fail-closed）；
 * - hosts 内非字符串/空白项过滤；
 * - 租户隔离：各租户拿到各自的 hosts；
 * - 无 token → 10200 信封（未认证）。
 * 附带（GitHub issue #2）：本 TestApp 刻意 **不** 注册 IdentifierGenerator——
 * starter 兜底（@ConditionalOnMissingBean）必须顶上，租户自增插入（不显式给 id）
 * 才能拿到雪花 id；老套件 TestApp 自带生成器会掩盖 starter 缺兜底的问题。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfyRuntimeOemTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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
                "framework4j.sensitive.enabled=true",
                "framework4j.sensitive.encryption-key=12345678901234567890123456789012"
        })
@AutoConfigureMockMvc
class NfyRuntimeOemTest {

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

        // 刻意无 IdentifierGenerator bean —— issue #2 验金石：starter 兜底必须生效

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

    private static final long TENANT_A = 89311L;
    private static final long TENANT_B = 89312L;

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
        // issue #2 验证：不显式给 id 的插入必须由 starter 兜底的 IdentifierGenerator 生成雪花 id
        NfyaTenant probe = new NfyaTenant();
        probe.setName("PROBE");
        probe.setStatus("ACTIVE");
        probe.setTenantSecret("probe-secret");
        tenantMapper.insert(probe);
        org.junit.jupiter.api.Assertions.assertNotNull(probe.getId(),
                "issue #2: starter 必须兜底 IdentifierGenerator（雪花 id）");
        // oem jsonb 参数化写（与 PlatformTenantService.patchJsonColumn 同口径）
        tenantMapper.update(null, new UpdateWrapper<NfyaTenant>().eq("id", TENANT_A)
                .setSql("oem = {0}::jsonb", "{\"theme\":\"dark\",\"title\":\"甲控制台\",\"hosts\":[\"https://app.example.com\",\"http://localhost:3001\",\"\"]}"));
        tenantMapper.update(null, new UpdateWrapper<NfyaTenant>().eq("id", TENANT_B)
                .setSql("oem = {0}::jsonb", "{\"hosts\":\"not-an-array\"}"));
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

    private String getHosts(String jwt) throws Exception {
        return mvc.perform(get("/nfy/api/v1/runtime/oem/hosts")
                        .header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void OEM001_配置租户回显_hosts_并过滤空白项() throws Exception {
        String body = getHosts(token(TENANT_A));
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("\"code\":0")
                .contains("https://app.example.com")
                .contains("http://localhost:3001")
                .doesNotContain("\"\"");
    }

    @Test
    void OEM002_hosts_非数组按空白名单处理() throws Exception {
        String body = getHosts(token(TENANT_B));
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("\"code\":0")
                .contains("\"hosts\":[]")
                .doesNotContain("not-an-array");
    }

    @Test
    void OEM003_租户隔离_各取各的_hosts() throws Exception {
        String b = getHosts(token(TENANT_B));
        org.assertj.core.api.Assertions.assertThat(b)
                .doesNotContain("app.example.com");
    }

    @Test
    void OEM004_无_token_未认证_10200() throws Exception {
        String body = mvc.perform(get("/nfy/api/v1/runtime/oem/hosts"))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).contains("\"code\":102");
    }
}
