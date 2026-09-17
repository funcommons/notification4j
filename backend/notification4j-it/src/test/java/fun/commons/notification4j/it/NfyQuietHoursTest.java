package fun.commons.notification4j.it;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import fun.commons.notification4j.entity.NfyaChannel;
import fun.commons.notification4j.entity.NfyaDelivery;
import fun.commons.notification4j.entity.NfyaMessageRecipient;
import fun.commons.notification4j.entity.NfyaTenant;
import fun.commons.notification4j.mapper.NfyaChannelMapper;
import fun.commons.notification4j.mapper.NfyaDeliveryMapper;
import fun.commons.notification4j.mapper.NfyaMessageRecipientMapper;
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
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 第 20 步：V1.2 第二特性——订阅免打扰时段 quiet_hours（API-SUB-001/002 扩展，§5.8）。
 * VECTOR: TAG=step20
 * 契约（产品设计文档 §2.2.2/§566；Courier quiet hours 口径）：
 * - SUB-002 items[] 每行可选 quiet_hours{start,end}：HH:mm 格式、start≠end、成对出现才算启用；
 *   缺省/{} = 未启用（零迁移，nfya_subscription.quiet_hours jsonb 已在）；SUB-001 items[] 原样回显；
 * - 语义=**推迟发送非丢弃**：订阅矩阵展开时当前时刻处于静默窗（跨午夜允许：t≥start 或 t<end）
 *   → 该订阅生成的外发投递 next_retry_at=窗结束时刻（窗前段 01:00→当日 end；窗后段 23:00→次日 end），
 *   窗结束由引擎按 next_retry_at<=now 自然发出，投递不消失；
 * - URGENT 走全量渠道路径不经订阅矩阵 → 完全忽略免打扰（用例 4 验证）；
 * - INAPP 站内信落库即达、不走 nfya_delivery → 不受免打扰影响（用例 5 验证）；
 * - 时间敏感用例不 sleep/不 mock 时钟：quiet_hours 是订阅行静态数据，直接构造覆盖当前时刻的窗口
 *   （start=当前 HH:mm-1h、end=+1h，跨午夜自然覆盖）；
 * - 公告订阅者外发路径（第 22 步 P2-4 登记）：ANNOUNCEMENT 发布展开（DeliveryPlanService.
 *   planForAnnouncement 订阅分支）对 quiet_hours 同判定——订阅 type_code=ANNOUNCEMENT 的用户
 *   静默窗内外发投递同样推迟，无静默窗对照用户即时（用例 7 验证）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfyQuietHoursTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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
@Tag("step20")
class NfyQuietHoursTest {

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
    @Autowired NfyaChannelMapper channelMapper;
    @Autowired NfyaDeliveryMapper deliveryMapper;
    @Autowired NfyaMessageRecipientMapper recipientMapper;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private static final long TENANT_A = 89601L;
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    @BeforeAll
    void init() throws Exception {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        NfyaTenant t = new NfyaTenant();
        t.setId(TENANT_A);
        t.setName("QH");
        t.setStatus("ACTIVE");
        t.setTenantSecret("secret-qh");
        tenantMapper.insert(t);
    }

    // ---------- 基础桩 ----------

    private String token() throws Exception {
        String body = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=" + TENANT_A + "&client_secret=secret-qh"))
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    private void createType(String jwt, String code) throws Exception {
        String body = mvc.perform(post("/nfy/api/v1/admin/types")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json")
                        .content("{\"type_code\":\"" + code + "\",\"name\":\"t\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThatCode0(body);
    }

    /** 直插用户外发渠道（引擎未启用不真发；EMAIL 免验证同理由：不走渠道验证接口） */
    private String insertChannel(String userid, String type) {
        NfyaChannel c = new NfyaChannel();
        c.setTenantId(TENANT_A);
        c.setScope("USER");
        c.setUserid(userid);
        c.setChannelType(type);
        c.setName("渠道-" + type + "-" + userid);
        c.setTarget("https://oapi.dingtalk.com/robot/send?access_token=" + type + "-" + userid);
        c.setSecret("");
        c.setKeyword("");
        c.setStatus("ENABLED");
        c.setFailCount(0);
        c.setExt("{}");
        channelMapper.insert(c);
        return String.valueOf(c.getId());
    }

    private String putSubs(String jwt, String userid, String itemsJson) throws Exception {
        return mvc.perform(put("/nfy/api/v1/runtime/subscriptions")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", userid)
                        .contentType("application/json").content("{\"items\":" + itemsJson + "}"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String getSubs(String jwt, String userid) throws Exception {
        return mvc.perform(get("/nfy/api/v1/runtime/subscriptions")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", userid))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String send(String jwt, String bizNo, String typeCode, String level, String userid) throws Exception {
        String body = "{\"type_code\":\"" + typeCode + "\",\"user_ids\":[\"" + userid + "\"],"
                + "\"title\":\"免打扰\",\"content\":\"c\",\"biz_no\":\"" + bizNo + "\""
                + (level == null ? "" : ",\"level\":\"" + level + "\"") + "}";
        return mvc.perform(post("/nfy/api/v1/runtime/messages")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", userid)
                        .contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 构造覆盖当前时刻的静默窗：start=当前-1h、end=当前+1h（跨午夜自然覆盖；不 sleep/不 mock 时钟） */
    private String windowCoveringNow() {
        LocalTime now = LocalTime.now(ZoneId.systemDefault());
        return window(now.minusHours(1), now.plusHours(1));
    }

    /** 构造远离当前时刻的静默窗：start=当前+3h、end=当前+4h（不含当前时刻） */
    private String windowAwayFromNow() {
        LocalTime now = LocalTime.now(ZoneId.systemDefault());
        return window(now.plusHours(3), now.plusHours(4));
    }

    private static String window(LocalTime start, LocalTime end) {
        return "{\"start\":\"" + HHMM.format(start) + "\",\"end\":\"" + HHMM.format(end) + "\"}";
    }

    private String item(String typeCode, String channelIdsJson, String quietHoursJson) {
        return "{\"type_code\":\"" + typeCode + "\",\"channel_ids\":" + channelIdsJson
                + (quietHoursJson == null ? "" : ",\"quiet_hours\":" + quietHoursJson) + "}";
    }

    private List<NfyaDelivery> deliveries(String userid) {
        return deliveryMapper.selectList(new LambdaQueryWrapper<NfyaDelivery>()
                .eq(NfyaDelivery::getTenantId, TENANT_A)
                .eq(NfyaDelivery::getSourceType, "MESSAGE")
                .eq(NfyaDelivery::getUserid, userid));
    }

    /** 公告外发投递（source_type=ANNOUNCEMENT）：发布展开（planForAnnouncement）产出 */
    private List<NfyaDelivery> announcementDeliveries(String userid) {
        return deliveryMapper.selectList(new LambdaQueryWrapper<NfyaDelivery>()
                .eq(NfyaDelivery::getTenantId, TENANT_A)
                .eq(NfyaDelivery::getSourceType, "ANNOUNCEMENT")
                .eq(NfyaDelivery::getUserid, userid));
    }

    private JsonNode itemsOf(String getBody, String typeCode) throws Exception {
        for (JsonNode n : objectMapper.readTree(getBody).path("data").path("items")) {
            if (typeCode.equals(n.path("type_code").asText())) {
                return n;
            }
        }
        return null;
    }

    // ---------- 用例 ----------

    @Test
    void save_and_echo_quiet_hours() throws Exception {
        String jwt = token();
        createType(jwt, "QH_ECHO");
        createType(jwt, "QH_NOQH");
        String dingId = insertChannel("qh_echo", "DINGTALK");

        assertThatCode0(putSubs(jwt, "qh_echo", "["
                + item("QH_ECHO", "[\"INAPP\",\"" + dingId + "\"]", "{\"start\":\"22:00\",\"end\":\"08:00\"}") + ","
                + item("QH_NOQH", "[\"INAPP\"]", null) + "]"));

        JsonNode echo = itemsOf(getSubs(jwt, "qh_echo"), "QH_ECHO");
        assertThat(echo).isNotNull();
        assertThat(echo.path("quiet_hours").path("start").asText()).isEqualTo("22:00");
        assertThat(echo.path("quiet_hours").path("end").asText()).isEqualTo("08:00");
        // 未启用行不返回 quiet_hours 字段（缺省=未启用，向后兼容旧客户端——items 精确断言回归保护）
        JsonNode noQh = itemsOf(getSubs(jwt, "qh_echo"), "QH_NOQH");
        assertThat(noQh).isNotNull();
        assertThat(noQh.has("quiet_hours")).isFalse();
    }

    @Test
    void full_replacement_without_quiet_hours_resets_to_unset() throws Exception {
        String jwt = token();
        createType(jwt, "QH_RESET");
        String dingId = insertChannel("qh_reset", "DINGTALK");
        assertThatCode0(putSubs(jwt, "qh_reset",
                "[" + item("QH_RESET", "[\"INAPP\",\"" + dingId + "\"]", windowCoveringNow()) + "]"));
        JsonNode withQh = itemsOf(getSubs(jwt, "qh_reset"), "QH_RESET");
        assertThat(withQh.path("quiet_hours").size()).isEqualTo(2);

        // PUT 全量替换语义：缺省 quiet_hours 的行重置为未启用（不再返回该字段）
        assertThatCode0(putSubs(jwt, "qh_reset",
                "[" + item("QH_RESET", "[\"INAPP\",\"" + dingId + "\"]", null) + "]"));
        JsonNode reset = itemsOf(getSubs(jwt, "qh_reset"), "QH_RESET");
        assertThat(reset.has("quiet_hours")).isFalse();
    }

    @Test
    void in_window_send_defers_outbound_delivery() throws Exception {
        String jwt = token();
        createType(jwt, "QH_IN");
        String dingId = insertChannel("qh_in", "DINGTALK");
        assertThatCode0(putSubs(jwt, "qh_in",
                "[" + item("QH_IN", "[\"INAPP\",\"" + dingId + "\"]", windowCoveringNow()) + "]"));

        Instant before = Instant.now();
        assertThatCode0(send(jwt, "qh-in-1", "QH_IN", null, "qh_in"));

        // 投递生成但被推迟：next_retry_at 至少 30 分钟后（即时投递=计划时刻 now，不可能 ≥30min），
        // 且落在窗结束附近（构造窗=2h，留 10min 富余）
        List<NfyaDelivery> rows = deliveries("qh_in");
        assertThat(rows).hasSize(1);
        NfyaDelivery d = rows.get(0);
        assertThat(d.getStatus()).isEqualTo("PENDING");
        assertThat(d.getNextRetryAt().toInstant())
                .as("静默窗内外发投递应推迟到窗结束: %s", d.getNextRetryAt())
                .isAfter(before.plusSeconds(1800));
        assertThat(d.getNextRetryAt().toInstant()).isBefore(before.plusSeconds(2 * 3600L + 600));
    }

    @Test
    void out_of_window_and_unset_send_immediately() throws Exception {
        String jwt = token();
        createType(jwt, "QH_OUT");
        String outDing = insertChannel("qh_out", "DINGTALK");
        String defDing = insertChannel("qh_def", "DINGTALK");
        assertThatCode0(putSubs(jwt, "qh_out",
                "[" + item("QH_OUT", "[\"INAPP\",\"" + outDing + "\"]", windowAwayFromNow()) + "]"));
        assertThatCode0(putSubs(jwt, "qh_def",
                "[" + item("QH_OUT", "[\"INAPP\",\"" + defDing + "\"]", null) + "]"));

        Instant before = Instant.now();
        assertThatCode0(send(jwt, "qh-out-1", "QH_OUT", null, "qh_out"));
        assertThatCode0(send(jwt, "qh-def-1", "QH_OUT", null, "qh_def"));

        // 窗外：即时可扫（维持现状 next_retry_at=now）；{} 未启用订阅：同现状
        for (String userid : List.of("qh_out", "qh_def")) {
            List<NfyaDelivery> rows = deliveries(userid);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getNextRetryAt().toInstant())
                    .as("%s 窗外/未启用投递应即时: %s", userid, rows.get(0).getNextRetryAt())
                    .isBefore(before.plusSeconds(60));
        }
    }

    @Test
    void urgent_ignores_quiet_hours_all_channels_immediate() throws Exception {
        String jwt = token();
        createType(jwt, "QH_URG");
        String dingId = insertChannel("qh_urg", "DINGTALK");
        insertChannel("qh_urg", "WECOM");
        // 矩阵只勾钉钉 + 静默窗覆盖当前时刻
        assertThatCode0(putSubs(jwt, "qh_urg",
                "[" + item("QH_URG", "[\"INAPP\",\"" + dingId + "\"]", windowCoveringNow()) + "]"));

        Instant before = Instant.now();
        assertThatCode0(send(jwt, "qh-ur-1", "QH_URG", "URGENT", "qh_urg"));

        // URGENT 全量 ENABLED 渠道（钉钉+企微=2 行，无视矩阵勾选），且全部即时（忽略免打扰）
        List<NfyaDelivery> rows = deliveries("qh_urg");
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(d -> {
            assertThat(d.getStatus()).isEqualTo("PENDING");
            assertThat(d.getNextRetryAt().toInstant())
                    .as("URGENT 完全忽略免打扰: %s", d.getNextRetryAt())
                    .isBefore(before.plusSeconds(60));
        });
    }

    @Test
    void inapp_recipient_unaffected_by_quiet_hours() throws Exception {
        String jwt = token();
        createType(jwt, "QH_INAPP");
        // 只订 INAPP（无外发渠道）+ 静默窗覆盖当前时刻
        assertThatCode0(putSubs(jwt, "qh_inapp",
                "[" + item("QH_INAPP", "[\"INAPP\"]", windowCoveringNow()) + "]"));

        assertThatCode0(send(jwt, "qh-inapp-1", "QH_INAPP", null, "qh_inapp"));

        // INAPP 落库即达：recipient 照常生成、不产外发投递
        Long recipients = recipientMapper.selectCount(new LambdaQueryWrapper<NfyaMessageRecipient>()
                .eq(NfyaMessageRecipient::getTenantId, TENANT_A)
                .eq(NfyaMessageRecipient::getUserid, "qh_inapp"));
        assertThat(recipients).isEqualTo(1);
        assertThat(deliveries("qh_inapp")).isEmpty();
    }

    /** 第 22 步 P2-4：公告订阅者外发路径（ANNOUNCEMENT 发布展开）quiet_hours 同判定 */
    @Test
    void announcement_subscriber_quiet_hours_defers_outbound_delivery() throws Exception {
        String jwt = token();
        // 注册 ANNOUNCEMENT 类型：订阅 PUT requireEnabled 闸 + planForAnnouncement 按 type_code='ANNOUNCEMENT' 匹配订阅行
        createType(jwt, "ANNOUNCEMENT");
        String quietDing = insertChannel("qh_ann_quiet", "DINGTALK");
        String openDing = insertChannel("qh_ann_open", "DINGTALK");
        // 静默窗用户（覆盖当前时刻）与对照用户（无静默窗）同订 ANNOUNCEMENT
        assertThatCode0(putSubs(jwt, "qh_ann_quiet",
                "[" + item("ANNOUNCEMENT", "[\"INAPP\",\"" + quietDing + "\"]", windowCoveringNow()) + "]"));
        assertThatCode0(putSubs(jwt, "qh_ann_open",
                "[" + item("ANNOUNCEMENT", "[\"INAPP\",\"" + openDing + "\"]", null) + "]"));

        // 平台（租户 admin 面）发布订阅型公告 → publish 同事务展开订阅者外发投递
        Instant before = Instant.now();
        String created = mvc.perform(post("/nfy/api/v1/admin/announcements")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json")
                        .content("{\"title\":\"公告免打扰\",\"content\":\"c\",\"effective_at\":"
                                + before.toEpochMilli() + ",\"expire_at\":"
                                + before.plusSeconds(86400).toEpochMilli() + ",\"biz_no\":\"qh-ann-1\"}"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThatCode0(created);
        String annId = objectMapper.readTree(created).path("data").path("announcement_id").asText();
        String published = mvc.perform(post("/nfy/api/v1/admin/announcements/" + annId + "/publish")
                        .header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThatCode0(published);

        // 静默窗用户：ANNOUNCEMENT 外发投递生成但被推迟（与 MESSAGE 路径共用 quietWindowEnd 判定）
        List<NfyaDelivery> quietRows = announcementDeliveries("qh_ann_quiet");
        assertThat(quietRows).hasSize(1);
        NfyaDelivery q = quietRows.get(0);
        assertThat(q.getStatus()).isEqualTo("PENDING");
        assertThat(q.getNextRetryAt().toInstant())
                .as("公告订阅者静默窗内外发投递应推迟: %s", q.getNextRetryAt())
                .isAfter(before.plusSeconds(1800));
        assertThat(q.getNextRetryAt().toInstant()).isBefore(before.plusSeconds(2 * 3600L + 600));
        // 对照用户：无静默窗 → 即时可扫
        List<NfyaDelivery> openRows = announcementDeliveries("qh_ann_open");
        assertThat(openRows).hasSize(1);
        assertThat(openRows.get(0).getNextRetryAt().toInstant())
                .as("无静默窗公告订阅者应即时: %s", openRows.get(0).getNextRetryAt())
                .isBefore(before.plusSeconds(60));
    }

    @Test
    void invalid_quiet_hours_rejected_10100() throws Exception {
        String jwt = token();
        createType(jwt, "QH_BAD");
        String dingId = insertChannel("qh_bad", "DINGTALK");
        String chans = "[\"INAPP\",\"" + dingId + "\"]";

        // 非法格式 25:00 / abc
        expectCode(putSubs(jwt, "qh_bad", "[" + item("QH_BAD", chans, "{\"start\":\"25:00\",\"end\":\"08:00\"}") + "]"), 10100);
        expectCode(putSubs(jwt, "qh_bad", "[" + item("QH_BAD", chans, "{\"start\":\"abc\",\"end\":\"08:00\"}") + "]"), 10100);
        // start == end
        expectCode(putSubs(jwt, "qh_bad", "[" + item("QH_BAD", chans, "{\"start\":\"22:00\",\"end\":\"22:00\"}") + "]"), 10100);
        // 只给 start（不成对）
        expectCode(putSubs(jwt, "qh_bad", "[" + item("QH_BAD", chans, "{\"start\":\"22:00\"}") + "]"), 10100);

        // 校验失败不落库（事务内校验先行，无半截状态）
        assertThat(itemsOf(getSubs(jwt, "qh_bad"), "QH_BAD")).isNull();
    }

    // ---------- 断言工具 ----------

    private void expectCode(String body, int code) {
        assertThat(body).contains("\"code\":" + code);
    }

    private void assertThatCode0(String body) {
        assertThat(body).contains("\"code\":0").doesNotContain("\"fail\":true");
    }
}
