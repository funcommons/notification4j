package fun.commons.notification4j.it;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import fun.commons.notification4j.entity.NfyaAnnouncement;
import fun.commons.notification4j.entity.NfyaChannel;
import fun.commons.notification4j.entity.NfyaDelivery;
import fun.commons.notification4j.entity.NfyaTenant;
import fun.commons.notification4j.mapper.NfyaAnnouncementMapper;
import fun.commons.notification4j.mapper.NfyaChannelMapper;
import fun.commons.notification4j.mapper.NfyaDeliveryMapper;
import fun.commons.notification4j.mapper.NfyaTenantMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 冒烟层（四层测试体系第 27 步）：一条顺序链 8 用例跑通核心业务闭环。
 * VECTOR: TAG=smoke
 *
 * <p>定位：快速健康证明（单套件冷启动 ≤90s），不是覆盖工具——覆盖由单测 + 全量 IT 负责。
 * 每步只断言主干通路（code=0 / 关键状态流转 / 口径数字），负路径与边界归全量 IT。</p>
 *
 * <p><b>顺序依赖说明（与常规 IT 纪律的刻意差异）：</b>常规 IT 要求用例相互独立、禁顺序依赖；
 * 本套件用 {@code @TestMethodOrder(OrderAnnotation)} 显式排序——理由：冒烟链本质是「一条业务事务」
 * （换 token → 建类型发消息 → 读 → 订阅 → 公告 → 撤回 → 投递运维 → 健康），前步产物（jwt、message_id、
 * channel_id、announcement_id）即后步输���；拆成独立用例则每步都要重复造前置数据，用例数与运行时长
 * 双双膨胀，违背冒烟「最少用例、最快反馈」的目标。链上任一步失败即定位到对应业务环节，
 * 修复后整链重跑（分钟级）。</p>
 *
 * <p>链路（8 步）：① client_credentials 换 token（含错误密钥被拒一次）② 建类型 + 发消息（3 接收人）
 * ③ 未读数=3 → 列表含 → 详情即已读 → 未读数=2 ④ 注册 EMAIL 渠道（免验证 mapper 置 ENABLED 离线手法）
 * + 订阅勾选 ⑤ 平台公告发布 → 用户可见 → 确认 → confirm_count=1 ⑥ 撤回 → 用户列表不含 → 未读口径不变
 * ⑦ admin 投递记录查询 + 仅 DEAD 可重投语义抽查 ⑧ ops health UP。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(classes = NfySmokeTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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
                "framework4j.access-token.exclude-path-patterns=/nfy/api/v1/auth/token,/nfy/api/v1/ops/health",
                "framework4j.access-token.policies.TENANT.key=tenant_id",
                "framework4j.access-token.policies.TENANT.expire-time=28800",
                "framework4j.access-token.policies.PLATFORM.key=tenant_id",
                "framework4j.access-token.policies.PLATFORM.expire-time=28800",
                "framework4j.sensitive.enabled=true",
                "framework4j.sensitive.encryption-key=12345678901234567890123456789012"
        })
@AutoConfigureMockMvc
@Tag("smoke")
class NfySmokeTest {

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

        // MP 雪花 ID 生成器(否则 id=null 插入即炸)
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
    @Autowired NfyaAnnouncementMapper announcementMapper;
    @Autowired NfyaChannelMapper channelMapper;
    @Autowired NfyaDeliveryMapper deliveryMapper;

    private static final long TENANT = 89701L;
    private static final String USER = "u_smoke_1";
    private static final String TITLE_BATCH = "冒烟-批量通知";

    // 链上前步产物（后步输入）
    private String jwt;
    private String platformJwt;
    private String batchMessageId;

    @BeforeAll
    void init() throws Exception {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        NfyaTenant t = new NfyaTenant();
        t.setId(TENANT);
        t.setName("冒烟租户");
        t.setStatus("ACTIVE");
        t.setTenantSecret("secret-89701");
        tenantMapper.insert(t);
    }

    // ---------- 链 ①：client_credentials 换 token（含错误密钥被拒一次） ----------

    @Test
    @Order(1)
    void step1_exchange_token() throws Exception {
        // 错误密钥被拒一次（非 0 业务码；max-fail=5 内仅此一次，不影响后续正确换取）
        String denied = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=" + TENANT + "&client_secret=wrong-secret"))
                .andReturn().getResponse().getContentAsString();
        assertThat(denied).contains("\"fail\":true");

        jwt = exchangeToken(String.valueOf(TENANT), "secret-89701");
        assertThat(jwt).isNotBlank();
        // 平台身份换 token（链 ⑤ 用）：client_id 必须是配置的平台字面量（framework4j.tenant.platform.client-id）
        platformJwt = exchangeToken("PLATFORM", "platform-secret-IT");
        assertThat(platformJwt).isNotBlank();
    }

    // ---------- 链 ②：创建类型 → 发消息（3 接收人） ----------

    @Test
    @Order(2)
    void step2_create_type_and_send_to_3_receivers() throws Exception {
        assertThatCode0(postJson(jwt, "/nfy/api/v1/admin/types", null,
                "{\"type_code\":\"SMOKE_ORDER\",\"name\":\"冒烟订单类型\",\"default_channels\":[\"INAPP\"]}"));

        String sent = postJson(jwt, "/nfy/api/v1/runtime/messages", null,
                "{\"type_code\":\"SMOKE_ORDER\",\"user_ids\":[\"" + USER + "\",\"u_smoke_2\",\"u_smoke_3\"],"
                        + "\"title\":\"" + TITLE_BATCH + "\",\"content\":\"订单 **OD1** 已支付\",\"biz_no\":\"smoke-biz-1\"}");
        assertThatCode0(sent);
        assertThat(sent).contains("\"receiver_count\":3").contains("\"inapp_saved\":true");
        batchMessageId = extract(sent, "message_id");

        // 再发 2 条单发（让 u_smoke_1 未读数凑到 3，供链 ③/⑥ 口径断言）
        assertThatCode0(postJson(jwt, "/nfy/api/v1/runtime/messages", null,
                "{\"type_code\":\"SMOKE_ORDER\",\"user_ids\":[\"" + USER + "\"],"
                        + "\"title\":\"冒烟-单发2\",\"content\":\"c\",\"biz_no\":\"smoke-biz-2\"}"));
        assertThatCode0(postJson(jwt, "/nfy/api/v1/runtime/messages", null,
                "{\"type_code\":\"SMOKE_ORDER\",\"user_ids\":[\"" + USER + "\"],"
                        + "\"title\":\"冒烟-单发3\",\"content\":\"c\",\"biz_no\":\"smoke-biz-3\"}"));
    }

    // ---------- 链 ③：未读数=3 → 列表含该消息 → 详情即已读 → 未读数=2 ----------

    @Test
    @Order(3)
    void step3_unread_list_detail_read() throws Exception {
        unreadCount(USER, 3);

        String list = getJson(jwt, "/nfy/api/v1/runtime/messages?read_status=UNREAD", USER);
        assertThat(list).contains(TITLE_BATCH);

        // 详情即已读（MSG-005 返回即置已读）
        assertThatCode0(getJson(jwt, "/nfy/api/v1/runtime/messages/" + batchMessageId, USER));
        unreadCount(USER, 2);
        // u_smoke_2 仍未读（链 ⑥ 撤回口径基线）
        unreadCount("u_smoke_2", 1);
    }

    // ---------- 链 ④：注册 EMAIL 渠道（免验证 mapper 置 ENABLED）→ 订阅勾选 ----------

    @Test
    @Order(4)
    void step4_register_email_channel_and_subscribe() throws Exception {
        String registered = postJson(jwt, "/nfy/api/v1/runtime/channels", USER,
                "{\"channel_type\":\"EMAIL\",\"name\":\"冒烟邮箱\",\"target\":\"smoke@example.com\"}");
        assertThatCode0(registered);
        assertThat(registered).contains("\"status\":\"PENDING\"");
        String channelId = extract(registered, "channel_id");

        // 免验证离线手法（等价验证完成后的终态，EMAIL SMTP 适配不在冒烟范围）：
        // 直置 ENABLED——既有 IT（NfyMessageCancelTest/NfyDeliveryPlanTest）同口径
        channelMapper.update(null, new LambdaUpdateWrapper<NfyaChannel>()
                .eq(NfyaChannel::getId, Long.valueOf(channelId))
                .eq(NfyaChannel::getStatus, "PENDING")
                .set(NfyaChannel::getStatus, "ENABLED"));
        assertThat(channelMapper.selectById(Long.valueOf(channelId)).getStatus()).isEqualTo("ENABLED");

        // 订阅类型勾选该渠道（PUT 全量替换，INAPP 哨兵服务端补齐）
        String saved = putJson(jwt, "/nfy/api/v1/runtime/subscriptions", USER,
                "{\"items\":[{\"type_code\":\"SMOKE_ORDER\",\"channel_ids\":[\"INAPP\",\"" + channelId + "\"]}]}");
        assertThatCode0(saved);
        assertThat(saved).contains("\"saved_count\":1");
        assertThat(getJson(jwt, "/nfy/api/v1/runtime/subscriptions", USER))
                .contains("\"type_code\":\"SMOKE_ORDER\"").contains(channelId);
    }

    // ---------- 链 ⑤：平台公告发布 → 用户列表含 → 确认 → confirm_count=1 ----------

    @Test
    @Order(5)
    void step5_platform_announcement_publish_and_confirm() throws Exception {
        String eff = String.valueOf(System.currentTimeMillis() - 1000);
        String exp = String.valueOf(System.currentTimeMillis() + 86400000L);
        String created = postJson(platformJwt, "/nfy/platform/api/v1/announcements", null,
                "{\"title\":\"冒烟-平台维护公告\",\"content\":\"c\",\"effective_at\":" + eff + ",\"expire_at\":" + exp
                        + ",\"need_confirm\":1,\"biz_no\":\"smoke-ann-1\"}");
        assertThatCode0(created);
        String annId = extract(created, "announcement_id");
        assertThatCode0(postJson(platformJwt, "/nfy/platform/api/v1/announcements/" + annId + "/publish", null, null));

        // 用户公告列表含（平台公告 tenant_id=0 全租户合并）
        String list = getJson(jwt, "/nfy/api/v1/runtime/announcements", USER);
        assertThat(list).contains("冒烟-平台维护公告").contains("\"my_status\":\"NONE\"");
        // 需确认公告进入未读合成口径（§5.3）
        mvc.perform(get("/nfy/api/v1/runtime/messages/unread-count")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unconfirmed_count").value(1));

        // 确认（幂等 uk 兜底在公告 IT 已验，冒烟只走主通路一次）。
        // 第 28 步口径修复：回执行归属域 = 公告归属租户（平台公告归 0 域）——租户用户确认平台公告
        // 后，PAN detail 的「回执实数」confirm_count 与公告冗余列同观（修前双口径：PAN 恒 0）。
        assertThatCode0(postJson(jwt, "/nfy/api/v1/runtime/announcements/" + annId + "/confirm", USER, null));
        // ① 用户侧回执生效：my_status CONFIRMED + unconfirmed 归零
        assertThat(getJson(jwt, "/nfy/api/v1/runtime/announcements", USER)).contains("\"my_status\":\"CONFIRMED\"");
        mvc.perform(get("/nfy/api/v1/runtime/messages/unread-count")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unconfirmed_count").value(0));
        // ② confirm_count=1：公告冗余列（confirm 原子 +1 落账）与 PAN detail 回执实数双口径一致
        NfyaAnnouncement ann =
                announcementMapper.selectById(Long.valueOf(annId));
        assertThat(ann).isNotNull();
        assertThat(ann.getConfirmCount()).isEqualTo(1);
        assertThat(getJson(platformJwt, "/nfy/platform/api/v1/announcements/" + annId, null))
                .contains("\"confirm_count\":\"1\"");
    }

    // ---------- 链 ⑥：撤回该消息 → 用户列表不含 → 未读数口径不变 ----------

    @Test
    @Order(6)
    void step6_cancel_message_and_unread_semantics() throws Exception {
        String cancelled = postJson(jwt, "/nfy/api/v1/runtime/messages/" + batchMessageId + "/cancel", null, null);
        assertThatCode0(cancelled);
        assertThat(extract(cancelled, "status")).isEqualTo("CANCELLED");
        // INAPP 落库即达、无站外投递可拦
        assertThat(cancelled).contains("\"cancelled_deliveries\":0");

        // 用户列表不含撤回消息；详情 10400
        assertThat(getJson(jwt, "/nfy/api/v1/runtime/messages", USER)).doesNotContain(TITLE_BATCH);
        mvc.perform(get("/nfy/api/v1/runtime/messages/" + batchMessageId)
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", USER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10400));

        // 未读数口径不变：已读的撤回消息不影响已读者（u_smoke_1 仍 2），未读者不再计入（u_smoke_2 1→0）
        unreadCount(USER, 2);
        unreadCount("u_smoke_2", 0);
    }

    // ---------- 链 ⑦：admin 投递记录查询 → 仅 DEAD 可重投语义抽查 ----------

    @Test
    @Order(7)
    void step7_admin_delivery_query_and_dead_only_retry() throws Exception {
        // 离线造 ENABLED 钉钉渠道 + 类型 default_channels 含 DINGTALK → 发送即计划 PENDING 投递（引擎不启用）
        NfyaChannel ch = new NfyaChannel();
        ch.setTenantId(TENANT);
        ch.setScope("USER");
        ch.setUserid("u_smoke_dl");
        ch.setChannelType("DINGTALK");
        ch.setName("冒烟钉钉渠道");
        ch.setTarget("https://oapi.dingtalk.com/robot/send?access_token=smoke-dlq-token");
        ch.setSecret("");
        ch.setKeyword("");
        ch.setStatus("ENABLED");
        ch.setFailCount(0);
        ch.setExt("{}");
        channelMapper.insert(ch);

        assertThatCode0(postJson(jwt, "/nfy/api/v1/admin/types", null,
                "{\"type_code\":\"SMOKE_DLQ\",\"name\":\"冒烟投递类型\",\"default_channels\":[\"INAPP\",\"DINGTALK\"]}"));
        String sent = postJson(jwt, "/nfy/api/v1/runtime/messages", null,
                "{\"type_code\":\"SMOKE_DLQ\",\"user_ids\":[\"u_smoke_dl\"],\"title\":\"冒烟-投递\",\"content\":\"c\",\"biz_no\":\"smoke-dlv-1\"}");
        assertThatCode0(sent);
        NfyaDelivery row = deliveryMapper.selectOne(new LambdaQueryWrapper<NfyaDelivery>()
                .eq(NfyaDelivery::getTenantId, TENANT)
                .eq(NfyaDelivery::getSourceId, Long.valueOf(extract(sent, "message_id")))
                .last("LIMIT 1"));
        assertThat(row).as("应已计划站外投递").isNotNull();
        assertThat(row.getStatus()).isEqualTo("PENDING");

        // DLV-001 查询：target 脱敏
        String list = getJson(jwt, "/nfy/api/v1/admin/deliveries?biz_no=smoke-dlv-1", null);
        assertThatCode0(list);
        assertThat(list).contains("oapi.dingtalk.com").contains("/robot/send?****")
                .doesNotContain("smoke-dlq-token");

        // DLV-002 语义抽查：PENDING 重投 10402 → 置 DEAD → 重投成功回 PENDING
        expectCode(postJson(jwt, "/nfy/api/v1/admin/deliveries/" + row.getId() + "/retry", null, null), 10402);
        deliveryMapper.update(null, new LambdaUpdateWrapper<NfyaDelivery>()
                .eq(NfyaDelivery::getId, row.getId())
                .eq(NfyaDelivery::getStatus, "PENDING")
                .set(NfyaDelivery::getStatus, "DEAD"));
        assertThatCode0(postJson(jwt, "/nfy/api/v1/admin/deliveries/" + row.getId() + "/retry", null, null));
        assertThat(deliveryMapper.selectById(row.getId()).getStatus()).isEqualTo("PENDING");
    }

    // ---------- 链 ⑧：ops health UP（无 token，exclude 放行） ----------

    @Test
    @Order(8)
    void step8_ops_health_up() throws Exception {
        mvc.perform(get("/nfy/api/v1/ops/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.checks.db.status").value("UP"))
                .andExpect(jsonPath("$.data.checks.redis.status").value("UP"));
    }

    // ---------- 复用助手 ----------

    private String exchangeToken(String clientId, String secret) throws Exception {
        String body = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=" + clientId + "&client_secret=" + secret))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).as("换 token 失败: %s", body).contains("access_token");
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    /** userid 非空时附带 X-User-Id（runtime 渠道注册等按用户口径的端点需要） */
    private String postJson(String jwt, String url, String userid, String body) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder =
                post(url).header("Authorization", "Bearer " + jwt)
                        .contentType("application/json").content(body == null ? "{}" : body);
        if (userid != null) {
            builder = builder.header("X-User-Id", userid);
        }
        return mvc.perform(builder)
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String putJson(String jwt, String url, String userid, String body) throws Exception {
        return mvc.perform(put(url).header("Authorization", "Bearer " + jwt).header("X-User-Id", userid)
                        .contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String getJson(String jwt, String url, String userid) throws Exception {
        return mvc.perform(userid == null ? get(url).header("Authorization", "Bearer " + jwt)
                        : get(url).header("Authorization", "Bearer " + jwt).header("X-User-Id", userid))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void unreadCount(String userid, long expected) throws Exception {
        mvc.perform(get("/nfy/api/v1/runtime/messages/unread-count")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", userid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unread_count").value(expected));
    }

    private String extract(String body, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\":\"([^\"]+)\"").matcher(body);
        assertThat(m.find()).as("字段 %s 存在: %s", field, body).isTrue();
        return m.group(1);
    }

    private void expectCode(String body, int code) {
        assertThat(body).contains("\"code\":" + code);
    }

    private void assertThatCode0(String body) {
        assertThat(body).contains("\"code\":0").doesNotContain("\"fail\":true");
    }
}
