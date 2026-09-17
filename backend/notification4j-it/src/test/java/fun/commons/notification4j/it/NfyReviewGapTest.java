package fun.commons.notification4j.it;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 18 步：评审登记 IT 缺口补齐（第 10~16 步评审 P2-8）。
 * VECTOR: TAG=step18-gaps
 * ① P0-2 回归：SUSPEND 撤销存量会话——挂起前签发的 token 在 SUSPEND 后被拒（revoker 真实生效；
 *    第 22 步强化：admin/runtime 两面同断实测）；
 * ② P1-1 边界：签名密钥 71 字符成功 / 72 → 10100；
 * ③ P2-8：并发扣减注册码（max_uses=1 两并发注册恰好一成功）；
 * ④ P2-8：模板渲染 $ 特殊字符（quoteReplacement 防注入）；
 * ⑤ P2-8：stats 零数据日字段齐全。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfyReviewGapTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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
@Tag("step18")
class NfyReviewGapTest {

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
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    private String createTenantViaPlatform(String plat, String name) throws Exception {
        String created = mvc.perform(post("/nfy/platform/api/v1/tenants")
                        .header("Authorization", "Bearer " + plat)
                        .contentType("application/json")
                        .content("{\"name\":\"" + name + "\",\"email\":\"" + name + "@gap.test\"}"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(created).contains("\"code\":0");
        return created;
    }

    /** ① P0-2 回归：SUSPEND 撤销存量会话——旧 token 调 admin API 被拒 */
    @Test
    void suspend_revokes_existing_session_token() throws Exception {
        String plat = platformToken();
        String created = createTenantViaPlatform(plat, "rev");
        String openId = extract(created, "open_id");
        String secret = extract(created, "tenant_secret");

        // 先签发存量 token
        String auth = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=" + openId + "&client_secret=" + secret))
                .andReturn().getResponse().getContentAsString();
        String oldToken = auth.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");

        // 存量 token 当前可用
        mvc.perform(get("/nfy/api/v1/admin/types").header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isOk());

        // SUSPEND（触发 revoker）→ 同一存量 token 被拒
        assertThatCode0(pPost(plat, "/nfy/platform/api/v1/tenants/" + openId + "/status",
                "{\"action\":\"SUSPEND\"}"));
        String after = mvc.perform(get("/nfy/api/v1/admin/types").header("Authorization", "Bearer " + oldToken))
                .andReturn().getResponse().getContentAsString();
        // 撤销后拦截：认证失败（非 0 code，且不是 500）
        org.assertj.core.api.Assertions.assertThat(after)
                .contains("\"fail\":true")
                .doesNotContain("\"code\":0");

        // 第 22 步登记（runtime 面强化）：同一存量 token 调 runtime 面接口同被拒。
        // 撤销机制=TenantSessionRevoker 删 Redis 会话元数据 key，framework4j access-token 过滤器
        // 对 admin/runtime 面统一 JWT+Redis 双验（同一拦截器）→ 两面同断，此处实测钉死 runtime 面
        String runtimeAfter = mvc.perform(get("/nfy/api/v1/runtime/messages")
                        .header("Authorization", "Bearer " + oldToken).header("X-User-Id", "rev-u1"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(runtimeAfter)
                .contains("\"fail\":true")
                .doesNotContain("\"code\":0");
    }

    /** ② P1-1 边界：71 成功 / 72 → 10100 */
    @Test
    void signature_key_length_boundary_71_72() throws Exception {
        String plat = platformToken();
        String created = createTenantViaPlatform(plat, "seclen");
        String openId = extract(created, "open_id");
        String secret = extract(created, "tenant_secret");
        String jwt = token(openId, secret);

        String k71 = "k".repeat(71);
        String ok = mvc.perform(post("/nfy/api/v1/admin/signature-key")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json").content("{\"secret\":\"" + k71 + "\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThatCode0(ok);

        String k72 = "k".repeat(72);
        String rejected = mvc.perform(post("/nfy/api/v1/admin/signature-key")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json").content("{\"secret\":\"" + k72 + "\"}"))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(rejected).contains("\"code\":10100");
    }

    /** ③ 并发扣减：max_uses=1 两并发注册恰好一成功 */
    @Test
    void concurrent_register_only_one_wins() throws Exception {
        String plat = platformToken();
        String issued = mvc.perform(post("/nfy/platform/api/v1/registration-keys")
                        .header("Authorization", "Bearer " + plat)
                        .contentType("application/json").content("{\"max_uses\":1,\"expire_hours\":24}"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String key = extract(issued, "registration_key");

        AtomicInteger wins = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(2);
        Runnable register = () -> {
            try {
                String body = mvc.perform(post("/nfy/open/api/v1/tenants/register")
                                .contentType("application/json")
                                .content("{\"registration_key\":\"" + key + "\",\"name\":\"并发\"}"))
                        .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                if (body.contains("\"code\":0")) {
                    wins.incrementAndGet();
                }
            } catch (Exception ignored) {
            } finally {
                latch.countDown();
            }
        };
        new Thread(register).start();
        new Thread(register).start();
        latch.await();

        org.assertj.core.api.Assertions.assertThat(wins.get()).isEqualTo(1);
    }

    /** ④ 模板渲染 $ 特殊字符（quoteReplacement 防注入） */
    @Test
    void template_render_with_dollar_value() throws Exception {
        String plat = platformToken();
        String created = createTenantViaPlatform(plat, "tpld");
        String openId = extract(created, "open_id");
        String secret = extract(created, "tenant_secret");
        String jwt = token(openId, secret);

        mvc.perform(post("/nfy/api/v1/admin/templates")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json")
                        .content("{\"template_code\":\"DOLLAR\",\"name\":\"d\",\"type_code\":\"T\","
                                + "\"title_tpl\":\"金额 ${{amount}} 结算\",\"content_tpl\":\"c\"}"))
                .andReturn().getResponse().getContentAsString();

        String rendered = mvc.perform(post("/nfy/api/v1/admin/templates/preview")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json")
                        .content("{\"template_code\":\"DOLLAR\",\"type_code\":\"T\","
                                + "\"params\":{\"amount\":\"$100&{{x}}\"}}"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThatCode0(rendered);
        // $ 与 {{}} 字面量原样保留（quoteReplacement 生效，无注入/无二次替换）
        org.assertj.core.api.Assertions.assertThat(rendered).contains("$100&{{x}}");
    }

    /** ⑤ stats 零数据日：新租户字段齐全不炸 */
    @Test
    void stats_zero_data_day_fields_present() throws Exception {
        String plat = platformToken();
        String created = createTenantViaPlatform(plat, "statsz");
        String openId = extract(created, "open_id");
        String secret = extract(created, "tenant_secret");
        String jwt = token(openId, secret);

        mvc.perform(get("/nfy/api/v1/admin/stats/overview").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.today_sent").value(0))
                .andExpect(jsonPath("$.data.today_delivered").value(0))
                .andExpect(jsonPath("$.data.channel_count").value(0))
                .andExpect(jsonPath("$.data.read_rate_7d").value(0));
    }

    private String token(String clientId, String clientSecret) throws Exception {
        String body = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=" + clientId + "&client_secret=" + clientSecret))
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    private String pPost(String jwt, String url, String body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + jwt)
                        .contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
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
