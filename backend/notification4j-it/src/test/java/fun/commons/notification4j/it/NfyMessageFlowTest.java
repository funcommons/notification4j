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
 * 第 3 步：消息发送 + 站内信闭环。
 * VECTOR: TAG=step3-message
 * 契约（API 设计文档 §5.2/§5.4/§5.5/§5.3）：类型管理（唯一 10401）；发送（biz_no 幂等闸 10401、
 * 类型停用 10601、接收人 1..1000）；未读数；Cursor 列表；批量已读；租户隔离（越权 10400）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfyMessageFlowTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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
                "framework4j.sensitive.encryption-key=12345678901234567890123456789012",
                "debug=true"
        })
@AutoConfigureMockMvc
@Tag("step3")
class NfyMessageFlowTest {

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

        // MP 雪花 ID 生成器(app 有 MybatisPlusIdGeneratorConfig, IT 需显式补——否则 id=null 插入即炸)
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

    private static final long TENANT_A = 88001L;
    private static final long TENANT_B = 88002L;

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

    private String createType(String jwt, String code) throws Exception {
        return mvc.perform(post("/nfy/api/v1/admin/types")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json")
                        .content("{\"type_code\":\"" + code + "\",\"name\":\"订单通知\",\"default_channels\":[\"INAPP\"]}"))
                .andReturn().getResponse().getContentAsString();
    }

    private String send(String jwt, String bizNo, String typeCode, String userId) throws Exception {
        String body = "{\"type_code\":\"" + typeCode + "\",\"user_ids\":[\"" + userId + "\"],"
                + "\"title\":\"订单支付成功\",\"content\":\"订单 **OD1** 已支付\""
                + (bizNo == null ? "" : ",\"biz_no\":\"" + bizNo + "\"") + "}";
        return mvc.perform(post("/nfy/api/v1/runtime/messages")
                        .header("Authorization", "Bearer " + jwt)
                        .header("X-User-Id", userId)
                        .contentType("application/json")
                        .content(body))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void type_crud_and_unique_gate() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        assertThatCode0(createType(jwt, "ORDER"));
        // 同码重复 → 10401
        mvc.perform(post("/nfy/api/v1/admin/types")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json")
                        .content("{\"type_code\":\"ORDER\",\"name\":\"dup\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10401));
    }

    @Test
    void send_then_unread_then_list_then_read() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        createType(jwt, "ORDER");
        String body = send(jwt, "biz-100", "ORDER", "u_1");
        assertThatCode0(body);
        org.assertj.core.api.Assertions.assertThat(body).contains("\"receiver_count\":1").contains("\"inapp_saved\":true");

        // 未读数 = 1
        mvc.perform(get("/nfy/api/v1/runtime/messages/unread-count")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unread_count").value(1));

        // 列表含该消息（UNREAD 筛选）
        String list = mvc.perform(get("/nfy/api/v1/runtime/messages?read_status=UNREAD")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_1"))
                .andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(list).contains("订单支付成功");

        // 全部已读 → 未读 0
        String readAll = mvc.perform(post("/nfy/api/v1/runtime/messages/read")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_1")
                        .contentType("application/json").content("{\"all\":true}"))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(readAll)
                .as("all 已读应成功: %s", readAll).contains("\"read_count\":1");
        mvc.perform(get("/nfy/api/v1/runtime/messages/unread-count")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unread_count").value(0));
    }

    @Test
    void biz_no_duplicate_rejected_10401_across_replay() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        createType(jwt, "PAY");
        assertThatCode0(send(jwt, "biz-dup", "PAY", "u_2"));
        // 同租户同 biz_no 二次发送（无 Idempotency-Key）→ 10401
        mvc.perform(post("/nfy/api/v1/runtime/messages")
                        .header("Authorization", "Bearer " + jwt)
                        .header("X-User-Id", "u_2")
                        .contentType("application/json")
                        .content("{\"type_code\":\"PAY\",\"user_ids\":[\"u_2\"],\"title\":\"t\",\"content\":\"c\",\"biz_no\":\"biz-dup\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10401));
    }

    @Test
    void disabled_or_missing_type_rejected_10601() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        mvc.perform(post("/nfy/api/v1/runtime/messages")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_1")
                        .contentType("application/json")
                        .content("{\"type_code\":\"NOPE\",\"user_ids\":[\"u_1\"],\"title\":\"t\",\"content\":\"c\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10601));
    }

    @Test
    void cross_tenant_isolation() throws Exception {
        String jwtA = token(TENANT_A, "secret-a");
        String jwtB = token(TENANT_B, "secret-b");
        createType(jwtA, "ISO");
        assertThatCode0(send(jwtA, "iso-1", "ISO", "u_a"));

        // B 租户列表看不到 A 的消息
        String listB = mvc.perform(get("/nfy/api/v1/runtime/messages")
                        .header("Authorization", "Bearer " + jwtB).header("X-User-Id", "u_a"))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(listB).doesNotContain("iso-1").doesNotContain("订单支付成功");

        // A 类型码对 B 不可见（B 建同名码不冲突 → 幂等命名空间 = tenant_id）
        assertThatCode0(mvc.perform(post("/nfy/api/v1/admin/types")
                        .header("Authorization", "Bearer " + jwtB)
                        .contentType("application/json")
                        .content("{\"type_code\":\"ISO\",\"name\":\"B 自建\"}"))
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void batch_send_read_by_ids_and_cursor_boundaries() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        createType(jwt, "BATCH");
        // 批量发送 300 人（批量 INSERT 通路）
        java.util.List<String> users = new java.util.ArrayList<>();
        for (int i = 0; i < 300; i++) {
            users.add("batch_u_" + i);
        }
        String body = mvc.perform(post("/nfy/api/v1/runtime/messages")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "batch_u_0")
                        .contentType("application/json")
                        .content("{\"type_code\":\"BATCH\",\"user_ids\":" + toJson(users)
                                + ",\"title\":\"批量\",\"content\":\"c\",\"biz_no\":\"batch-1\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThatCode0(body);
        org.assertj.core.api.Assertions.assertThat(body).contains("\"receiver_count\":300");

        // read by ids：batch_u_0 标记自身已读
        String list0 = mvc.perform(get("/nfy/api/v1/runtime/messages?read_status=UNREAD&limit=1")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "batch_u_0"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"message_id\":\"(\\d+)\"")
                .matcher(list0);
        org.assertj.core.api.Assertions.assertThat(m.find()).isTrue();
        mvc.perform(post("/nfy/api/v1/runtime/messages/read")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "batch_u_0")
                        .contentType("application/json").content("{\"message_ids\":[\"" + m.group(1) + "\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.read_count").value(1));

        // ids + all 双传 → 10101
        String dual = mvc.perform(post("/nfy/api/v1/runtime/messages/read")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "batch_u_1")
                        .contentType("application/json").content("{\"all\":true,\"message_ids\":[\"1\"]}"))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(dual)
                .as("双传应 10101，实际: %s", dual).contains("\"code\":10101");

        // 非法 cursor → 10102
        mvc.perform(get("/nfy/api/v1/runtime/messages?cursor=%%%20bad")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "batch_u_1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10102));

        // cursor 翻页：batch_u_1 另有 7 条独立消息 → limit=5 首页 has_more，cursor 翻页无重复
        for (int i = 2; i <= 8; i++) {
            assertThatCode0(send(jwt, "biz-page-" + i, "BATCH", "batch_u_1"));
        }
        String p1 = mvc.perform(get("/nfy/api/v1/runtime/messages?limit=5")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "batch_u_1"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher cm = java.util.regex.Pattern.compile("\"next_cursor\":\"([^\"]+)\"").matcher(p1);
        org.assertj.core.api.Assertions.assertThat(cm.find()).as("首页应有 next_cursor: %s", p1).isTrue();
        String p2 = mvc.perform(get("/nfy/api/v1/runtime/messages?limit=5&cursor=" + cm.group(1))
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "batch_u_1"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String id1 = p1.split("\"message_id\":\"")[1].split("\"")[0];
        org.assertj.core.api.Assertions.assertThat(p2).doesNotContain("\"message_id\":\"" + id1 + "\"");
    }

    private String toJson(java.util.List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(items.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    private void assertThatCode0(String body) {
        org.assertj.core.api.Assertions.assertThat(body).contains("\"code\":0").doesNotContain("\"fail\":true");
    }
}
