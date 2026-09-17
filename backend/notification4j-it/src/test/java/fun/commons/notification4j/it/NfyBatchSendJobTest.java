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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 15 步：V1.1 第一批——批量发送与 Job 轮询（API-MSG-002 + API-JOB-001，§5.9.1）。
 * VECTOR: TAG=step15-batch
 * 契约：
 * - MSG-002 body 同 MSG-001 但 user_ids ≤100000 → 立即返回 {job_id, poll_url}，后台异步执行；
 * - JOB-001 GET /runtime/jobs/{job_id} → {job_id, status(RUNNING/DONE/PARTIAL/FAILED), total, finished, failed_items≤100}；
 * - 实现：每批 1000 人一条 message（biz_no 加批次后缀），逐批事务插 recipient+投递计划；
 *   Job 状态存 Redis（TTL 48h）；第一批前置校验失败（类型停用/空列表）→ job 直接 FAILED。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfyBatchSendJobTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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
@Tag("step15")
class NfyBatchSendJobTest {

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

    private static final long TENANT_A = 89401L;

    @BeforeAll
    void init() throws Exception {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        NfyaTenant t = new NfyaTenant();
        t.setId(TENANT_A);
        t.setName("A");
        t.setStatus("ACTIVE");
        t.setTenantSecret("secret-a");
        tenantMapper.insert(t);
    }

    private String token() throws Exception {
        String body = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=" + TENANT_A + "&client_secret=secret-a"))
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    private String batchSend(String jwt, String typeCode, String usersJson) throws Exception {
        String body = "{\"type_code\":\"" + typeCode + "\",\"user_ids\":" + usersJson
                + ",\"title\":\"批量\",\"content\":\"c\"}";
        return mvc.perform(post("/nfy/api/v1/runtime/messages/batch")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "batch_u")
                        .contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void batch_creates_job_and_completes_done() throws Exception {
        String jwt = token();
        assertThatCode0(mvc.perform(post("/nfy/api/v1/admin/types")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json")
                        .content("{\"type_code\":\"BATCH_T\",\"name\":\"t\"}"))
                .andReturn().getResponse().getContentAsString());

        StringBuilder users = new StringBuilder("[");
        for (int i = 0; i < 30; i++) {
            if (i > 0) users.append(',');
            users.append("\"jb_u_").append(i).append('"');
        }
        users.append(']');

        String accepted = batchSend(jwt, "BATCH_T", users.toString());
        assertThatCode0(accepted);
        String jobId = extract(accepted, "job_id");
        org.assertj.core.api.Assertions.assertThat(accepted).contains("poll_url").contains("\"total\":30");

        // 轮询至终态（异步执行；Redis 状态机）
        String status = pollToTerminal(jwt, jobId);
        org.assertj.core.api.Assertions.assertThat(status).isIn("DONE", "PARTIAL");
        if ("DONE".equals(status)) {
            mvc.perform(get("/nfy/api/v1/runtime/jobs/" + jobId)
                            .header("Authorization", "Bearer " + jwt).header("X-User-Id", "batch_u"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.finished").value(30));
        }
    }

    @Test
    void batch_over_1000_crosses_batch_boundary() throws Exception {
        String jwt = token();
        assertThatCode0(mvc.perform(post("/nfy/api/v1/admin/types")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json")
                        .content("{\"type_code\":\"BIG_T\",\"name\":\"t\"}"))
                .andReturn().getResponse().getContentAsString());
        StringBuilder users = new StringBuilder("[");
        for (int i = 0; i < 1500; i++) {
            if (i > 0) users.append(',');
            users.append("\"big_u_").append(i).append('"');
        }
        users.append(']');
        String accepted = batchSend(jwt, "BIG_T", users.toString());
        assertThatCode0(accepted);
        String jobId = extract(accepted, "job_id");
        String status = pollToTerminal(jwt, jobId);
        org.assertj.core.api.Assertions.assertThat(status).isEqualTo("DONE");
        // >1000 人数跨越批边界（P0-1 修复回归：注解层不再锁死 1000）
        mvc.perform(get("/nfy/api/v1/runtime/jobs/" + jobId)
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "batch_u"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.finished").value(1500));
    }

    @Test
    void batch_empty_users_and_unknown_type_failures() throws Exception {
        String jwt = token();
        // 空列表 → 10100（@Valid @NotEmpty 先行，fwk4j PARAM_ERROR 口径）
        expectCode(batchSend(jwt, "ANY", "[]"), 10100);

        // 未知类型 → job 直接 FAILED（异步执行第一批前置校验失败）
        String accepted = batchSend(jwt, "GHOST_TYPE", "[\"jb_x_1\",\"jb_x_2\"]");
        assertThatCode0(accepted);
        String jobId = extract(accepted, "job_id");
        String status = pollToTerminal(jwt, jobId);
        org.assertj.core.api.Assertions.assertThat(status).isEqualTo("FAILED");
    }

    private String pollToTerminal(String jwt, String jobId) throws Exception {
        for (int i = 0; i < 100; i++) {
            String body = mvc.perform(get("/nfy/api/v1/runtime/jobs/" + jobId)
                            .header("Authorization", "Bearer " + jwt).header("X-User-Id", "batch_u"))
                    .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            if (body.contains("\"status\":\"DONE\"") || body.contains("\"status\":\"PARTIAL\"")
                    || body.contains("\"status\":\"FAILED\"")) {
                return body.contains("\"status\":\"DONE\"") ? "DONE"
                        : body.contains("\"status\":\"PARTIAL\"") ? "PARTIAL" : "FAILED";
            }
            Thread.sleep(200);
        }
        throw new AssertionError("job 未达终态: " + jobId);
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
