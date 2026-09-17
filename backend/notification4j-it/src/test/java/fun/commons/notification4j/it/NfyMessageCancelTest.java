package fun.commons.notification4j.it;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import fun.commons.notification4j.entity.NfyaChannel;
import fun.commons.notification4j.entity.NfyaDelivery;
import fun.commons.notification4j.entity.NfyaMessageType;
import fun.commons.notification4j.entity.NfyaTenant;
import fun.commons.notification4j.mapper.NfyaChannelMapper;
import fun.commons.notification4j.mapper.NfyaDeliveryMapper;
import fun.commons.notification4j.mapper.NfyaMessageMapper;
import fun.commons.notification4j.mapper.NfyaMessageTypeMapper;
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
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 19 步：V1.2 第一特性——消息撤回（API-MSG-009，接口设计文档 §5.5.2）。
 * VECTOR: TAG=step19-cancel
 * 契约：
 * - POST /nfy/api/v1/runtime/messages/{message_id}/cancel（T 鉴权，发送方业务系统行为）；
 * - 属主校验：message.tenant_id ≠ 当前租户 / 不存在 / 非数字 id → 一律 10400 同文案「消息不存在或无权访问」（防探测）；
 * - 状态机 SENT→CANCELLED（条件 UPDATE 前置）；已 CANCELLED 再撤 = 幂等成功（cancelled_deliveries=0）；
 * - 级联外发拦截：nfya_delivery 仅 PENDING → CANCELLED（CAS 带 status='PENDING' 前置，防与引擎 claim/回写竞态；
 *   已 SENDING/SUCCESS 不追回——at-least-once）；响应含 cancelled_deliveries 计数；
 * - 用户侧可见性：撤回后 MSG-004 列表 / MSG-005 详情（10400）/ MSG-007 未读数不再计入；
 * - biz_no 幂等记录不动：同 biz_no 重发仍 10401；MSG-003 send-results 保持返回撤回消息（运营排查口径）。
 * 引擎不启用（nfy.runtime.engine.enabled 默认 false）：PENDING 投递稳定留存供撤回拦截；
 * 「已投递」场景用 mapper 直置 SUCCESS 模拟（等价引擎回写终态，非本特性被测路径）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfyMessageCancelTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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
@Tag("step19")
class NfyMessageCancelTest {

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
    @Autowired NfyaMessageTypeMapper typeMapper;
    @Autowired NfyaChannelMapper channelMapper;
    @Autowired NfyaDeliveryMapper deliveryMapper;
    @Autowired NfyaMessageMapper messageMapper;

    private static final long TENANT_A = 89501L;
    private static final long TENANT_B = 89502L;

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

    private String createType(String jwt, String code, String defaultChannels) throws Exception {
        return mvc.perform(post("/nfy/api/v1/admin/types")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json")
                        .content("{\"type_code\":\"" + code + "\",\"name\":\"撤回测试类型\",\"default_channels\":" + defaultChannels + "}"))
                .andReturn().getResponse().getContentAsString();
    }

    private void insertDingChannel(long tenantId, String userid, String marker) {
        NfyaChannel ch = new NfyaChannel();
        ch.setTenantId(tenantId);
        ch.setScope("USER");
        ch.setUserid(userid);
        ch.setChannelType("DINGTALK");
        ch.setName("渠道-" + userid);
        ch.setTarget("https://oapi.dingtalk.com/robot/send?access_token=" + marker);
        ch.setSecret("");
        ch.setKeyword("");
        ch.setStatus("ENABLED");
        ch.setFailCount(0);
        ch.setExt("{}");
        channelMapper.insert(ch);
    }

    /** API 发送（biz_no 唯一由调用方保证）→ 返回信封原文 */
    private String send(String jwt, String typeCode, String userid, String bizNo, String title) throws Exception {
        String body = "{\"type_code\":\"" + typeCode + "\",\"user_ids\":[\"" + userid + "\"],"
                + "\"title\":\"" + title + "\",\"content\":\"c\",\"biz_no\":\"" + bizNo + "\"}";
        return mvc.perform(post("/nfy/api/v1/runtime/messages")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", userid)
                        .contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String cancel(String jwt, String messageId) throws Exception {
        return mvc.perform(post("/nfy/api/v1/runtime/messages/" + messageId + "/cancel")
                        .header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String extract(String body, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\":\"([^\"]+)\"").matcher(body);
        assertThat(m.find()).as("字段 %s 存在: %s", field, body).isTrue();
        return m.group(1);
    }

    /** 整型字段（Long→String 口径仅作用于雪花 id；int 计数序列化为 JSON 数字） */
    private int extractInt(String body, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\":(\\d+)").matcher(body);
        assertThat(m.find()).as("字段 %s 存在: %s", field, body).isTrue();
        return Integer.parseInt(m.group(1));
    }

    /** 造「类型(default_channels 含 DINGTALK)+用户 ENABLED 钉钉渠道」并发消息 → 返回 PENDING 投递行 */
    private NfyaDelivery sendCreatingPendingDelivery(String jwt, String typeCode, String userid,
                                                     String bizNo, String title) throws Exception {
        createType(jwt, typeCode, "[\"INAPP\",\"DINGTALK\"]");
        insertDingChannel(TENANT_A, userid, bizNo);
        String body = send(jwt, typeCode, userid, bizNo, title);
        assertThat(body).contains("\"code\":0");
        Long messageId = Long.valueOf(extract(body, "message_id"));
        NfyaDelivery d = deliveryMapper.selectOne(new LambdaQueryWrapper<NfyaDelivery>()
                .eq(NfyaDelivery::getTenantId, TENANT_A)
                .eq(NfyaDelivery::getSourceType, "MESSAGE")
                .eq(NfyaDelivery::getSourceId, messageId)
                .last("LIMIT 1"));
        assertThat(d).as("应已计划 1 条站外投递").isNotNull();
        assertThat(d.getStatus()).isEqualTo("PENDING");
        return d;
    }

    @Test
    void cancel_success_pending_delivery_cancelled() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        NfyaDelivery d = sendCreatingPendingDelivery(jwt, "CXL_T1", "cx_u1", "cxl-biz-1", "撤回目标消息");
        String messageId = String.valueOf(d.getSourceId());

        // 撤回：code=0、status=CANCELLED、cancelled_deliveries≥1
        String cancelled = cancel(jwt, messageId);
        assertThat(cancelled).contains("\"code\":0").doesNotContain("\"fail\":true");
        assertThat(extract(cancelled, "status")).isEqualTo("CANCELLED");
        assertThat(extractInt(cancelled, "cancelled_deliveries")).isGreaterThanOrEqualTo(1);
        assertThat(deliveryMapper.selectById(d.getId()).getStatus()).isEqualTo("CANCELLED");

        // biz_no 幂等记录不动：同 biz_no 重发仍 10401
        assertThat(send(jwt, "CXL_T1", "cx_u1", "cxl-biz-1", "重发")).contains("\"code\":10401");

        // MSG-003 send-results 运营排查口径：撤回消息仍返回，status=CANCELLED，投递为 CANCELLED
        String results = mvc.perform(get("/nfy/api/v1/runtime/send-results/cxl-biz-1")
                        .header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(results).contains("\"code\":0").contains("\"status\":\"CANCELLED\"");

        // 用户侧可见性：列表不含、详情 10400、未读数不计撤回消息
        String list = mvc.perform(get("/nfy/api/v1/runtime/messages")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "cx_u1"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(list).doesNotContain("撤回目标消息");
        mvc.perform(get("/nfy/api/v1/runtime/messages/" + messageId)
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "cx_u1"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value(10400));
        mvc.perform(get("/nfy/api/v1/runtime/messages/unread-count")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "cx_u1"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.unread_count").value(0));
    }

    @Test
    void cancel_idempotent() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        createType(jwt, "CXL_T2", "[\"INAPP\"]");
        String sent = send(jwt, "CXL_T2", "cx_u2", "cxl-biz-2", "幂等撤回");
        assertThat(sent).contains("\"code\":0");
        String messageId = extract(sent, "message_id");

        String first = cancel(jwt, messageId);
        assertThat(first).contains("\"code\":0");
        assertThat(extractInt(first, "cancelled_deliveries")).isZero();

        // 第二次撤回：仍 code=0（幂等成功，不重复计数）
        String second = cancel(jwt, messageId);
        assertThat(second).contains("\"code\":0").doesNotContain("\"fail\":true");
        assertThat(extract(second, "status")).isEqualTo("CANCELLED");
        assertThat(extractInt(second, "cancelled_deliveries")).isZero();
    }

    @Test
    void cancel_cross_tenant_denied() throws Exception {
        String jwtA = token(TENANT_A, "secret-a");
        String jwtB = token(TENANT_B, "secret-b");
        createType(jwtA, "CXL_T3", "[\"INAPP\"]");
        String sent = send(jwtA, "CXL_T3", "cx_u3", "cxl-biz-3", "A 的消息");
        assertThat(sent).contains("\"code\":0");
        String messageId = extract(sent, "message_id");

        // B 撤 A 的消息 → 10400 同文案防探测
        String denied = cancel(jwtB, messageId);
        assertThat(denied).contains("\"code\":10400").contains("消息不存在或无权访问");
        // A 的消息不受影响（DB 仍 SENT，用户侧仍可见）
        assertThat(messageMapper.selectById(Long.valueOf(messageId)).getStatus()).isEqualTo("SENT");
        String list = mvc.perform(get("/nfy/api/v1/runtime/messages")
                        .header("Authorization", "Bearer " + jwtA).header("X-User-Id", "cx_u3"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(list).contains("A 的消息");
    }

    @Test
    void cancel_invalid_id() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        // 非数字 id 解析失败 → 同 10400 防探测（不暴露参数校验语义）
        String bad = cancel(jwt, "not-a-number");
        assertThat(bad).contains("\"code\":10400").contains("消息不存在或无权访问");
        // 不存在的数字 id 同口径
        assertThat(cancel(jwt, "1")).contains("\"code\":10400").contains("消息不存在或无权访问");
    }

    @Test
    void cancel_after_delivered() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        NfyaDelivery d = sendCreatingPendingDelivery(jwt, "CXL_T5", "cx_u5", "cxl-biz-5", "已投递后撤回");
        // 直置 SUCCESS（等价引擎回写终态；本 IT 引擎不启用）
        deliveryMapper.update(null, new LambdaUpdateWrapper<NfyaDelivery>()
                .eq(NfyaDelivery::getId, d.getId())
                .eq(NfyaDelivery::getStatus, "PENDING")
                .set(NfyaDelivery::getStatus, "SUCCESS")
                .set(NfyaDelivery::getSentAt, OffsetDateTime.now()));

        String cancelled = cancel(jwt, String.valueOf(d.getSourceId()));
        assertThat(cancelled).contains("\"code\":0");
        // 已在线上的不追回：cancelled_deliveries=0，投递保持 SUCCESS
        assertThat(extractInt(cancelled, "cancelled_deliveries")).isZero();
        assertThat(deliveryMapper.selectById(d.getId()).getStatus()).isEqualTo("SUCCESS");
        String results = mvc.perform(get("/nfy/api/v1/runtime/send-results/cxl-biz-5")
                        .header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(results).contains("\"status\":\"SUCCESS\"");
    }
}
