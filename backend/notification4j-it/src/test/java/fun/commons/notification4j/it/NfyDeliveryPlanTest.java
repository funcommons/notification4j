package fun.commons.notification4j.it;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import fun.commons.notification4j.entity.NfyaChannel;
import fun.commons.notification4j.entity.NfyaMessageType;
import fun.commons.notification4j.entity.NfyaSubscription;
import fun.commons.notification4j.entity.NfyaTenant;
import fun.commons.notification4j.mapper.NfyaAnnouncementMapper;
import fun.commons.notification4j.mapper.NfyaChannelMapper;
import fun.commons.notification4j.mapper.NfyaDeliveryMapper;
import fun.commons.notification4j.mapper.NfyaMessageMapper;
import fun.commons.notification4j.mapper.NfyaMessageTypeMapper;
import fun.commons.notification4j.mapper.NfyaSubscriptionMapper;
import fun.commons.notification4j.mapper.NfyaTenantMapper;
import fun.commons.notification4j.entity.NfyaDelivery;
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
import java.net.InetAddress;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 第 6a 步：投递计划（send/publish → nfya_delivery PENDING 行；引擎执行在第 6b 步）。
 * VECTOR: TAG=step6a-delivery
 * 契约（PRD F-MSG-001/§5.8/技术方案 §4.3）：
 * - 发送展开：无订阅行按类型 default_channels「类型语义→该类型全部 ENABLED 实例」；
 *   有订阅行按 channel_ids 实例（发送时过滤非 ENABLED/已删渠道）；INAPP 哨兵不产 delivery 行（站内信已落）。
 * - URGENT 等级：INAPP 恒投 + 该用户全部 ENABLED 注册渠道（无视矩阵勾选）。
 * - 公告发布：勾选公共渠道（scope=TENANT，userid=''）+ 订阅 ANNOUNCEMENT 类型用户的站外实例渠道。
 * - uk_nfya_delivery_source 幂等真闸；DLV-001 admin 查询（target 脱敏）；DLV-002 仅 DEAD 可重投（10402）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfyDeliveryPlanTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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
@Tag("step6")
class NfyDeliveryPlanTest {

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

        @Bean
        fun.commons.notification4j.service.WebhookTargetResolver webhookTargetResolver() {
            return host -> {
                if (host.endsWith("feishu.cn") || host.equals("oapi.dingtalk.com") || host.equals("qyapi.weixin.qq.com")) {
                    return new InetAddress[]{InetAddress.getByAddress(host, new byte[]{(byte) 203, 0, (byte) 113, 7})};
                }
                throw new java.net.UnknownHostException(host);
            };
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
    @Autowired NfyaSubscriptionMapper subscriptionMapper;
    @Autowired NfyaMessageMapper messageMapper;
    @Autowired NfyaAnnouncementMapper announcementMapper;
    @Autowired NfyaDeliveryMapper deliveryMapper;

    private static final long TENANT_A = 88501L;
    private static final long TENANT_B = 88502L;

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

    private void insertType(long tenantId, String code, String defaultChannels) {
        NfyaMessageType t = new NfyaMessageType();
        t.setTenantId(tenantId);
        t.setTypeCode(code);
        t.setName("类型-" + code);
        t.setDefaultLevel("NORMAL");
        t.setDefaultChannels(defaultChannels);
        t.setMandatory(0);
        t.setBuiltIn(0);
        t.setStatus("ENABLED");
        t.setExt("{}");
        typeMapper.insert(t);
    }

    /** 直插渠道（status 可控）；返回 id */
    private String insertChannel(long tenantId, String userid, String type, String status) {
        NfyaChannel c = new NfyaChannel();
        c.setTenantId(tenantId);
        c.setScope("USER");
        c.setUserid(userid);
        c.setChannelType(type);
        c.setName("渠道-" + type);
        c.setTarget("https://oapi.dingtalk.com/robot/send?access_token=" + type + "-" + userid);
        c.setSecret("");
        c.setKeyword("");
        c.setStatus(status);
        c.setFailCount(0);
        c.setExt("{}");
        channelMapper.insert(c);
        return String.valueOf(c.getId());
    }

    private void insertSubscription(long tenantId, String userid, String typeCode, String channelIds) {
        NfyaSubscription s = new NfyaSubscription();
        s.setTenantId(tenantId);
        s.setUserid(userid);
        s.setTypeCode(typeCode);
        s.setChannelIds(channelIds);
        s.setQuietHours("{}");
        s.setExt("{}");
        subscriptionMapper.insert(s);
    }

    private Long deliveryCount(long tenantId, String sourceType, String userid) {
        return deliveryMapper.selectCount(new LambdaQueryWrapper<NfyaDelivery>()
                .eq(NfyaDelivery::getTenantId, tenantId)
                .eq(NfyaDelivery::getSourceType, sourceType)
                .eq(userid == null ? NfyaDelivery::getSourceId : NfyaDelivery::getUserid,
                        userid == null ? 0L : userid));
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
                        .content("{\"type_code\":\"" + code + "\",\"name\":\"t\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    private String send(String jwt, String bizNo, String typeCode, String level, String userId) throws Exception {
        String body = "{\"type_code\":\"" + typeCode + "\",\"user_ids\":[\"" + userId + "\"],"
                + "\"title\":\"t\",\"content\":\"c\",\"biz_no\":\"" + bizNo + "\""
                + (level == null ? "" : ",\"level\":\"" + level + "\"") + "}";
        return mvc.perform(post("/nfy/api/v1/runtime/messages")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", userId)
                        .contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void send_expands_default_channels_and_subscription_matrix() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        // admin API 建类型默认 default_channels=["INAPP"]，此处直插含 DINGTALK 默认
        insertType(TENANT_A, "EXP", "[\"INAPP\",\"DINGTALK\"]");
        // u_def：有 ENABLED 钉钉渠道 → 默认策略产 1 行
        String dingId = insertChannel(TENANT_A, "u_def", "DINGTALK", "ENABLED");
        assertThatCode0(send(jwt, "dp-1", "EXP", null, "u_def"));
        org.assertj.core.api.Assertions.assertThat(deliveryCount(TENANT_A, "MESSAGE", "u_def")).isEqualTo(1);

        // u_sub：有钉钉渠道但订阅行只选 WECOM → 仅 WECOM 1 行（矩阵覆盖默认）
        String wecomId = insertChannel(TENANT_A, "u_sub", "WECOM", "ENABLED");
        insertChannel(TENANT_A, "u_sub", "DINGTALK", "ENABLED");
        insertSubscription(TENANT_A, "u_sub", "EXP", "[\"INAPP\",\"" + wecomId + "\"]");
        assertThatCode0(send(jwt, "dp-2", "EXP", null, "u_sub"));
        org.assertj.core.api.Assertions.assertThat(deliveryCount(TENANT_A, "MESSAGE", "u_sub")).isEqualTo(1);
        Long rows = deliveryMapper.selectCount(new LambdaQueryWrapper<NfyaDelivery>()
                .eq(NfyaDelivery::getTenantId, TENANT_A).eq(NfyaDelivery::getUserid, "u_sub")
                .eq(NfyaDelivery::getChannelId, Long.valueOf(wecomId)));
        org.assertj.core.api.Assertions.assertThat(rows).isEqualTo(1);

        // u_none：无渠道 → 0 行（站内信仍达）
        assertThatCode0(send(jwt, "dp-3", "EXP", null, "u_none"));
        org.assertj.core.api.Assertions.assertThat(deliveryCount(TENANT_A, "MESSAGE", "u_none")).isEqualTo(0);
    }

    @Test
    void urgent_level_ignores_matrix_all_enabled_channels() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        insertType(TENANT_A, "URG", "[\"INAPP\"]");
        insertChannel(TENANT_A, "u_ur", "DINGTALK", "ENABLED");
        insertChannel(TENANT_A, "u_ur", "WECOM", "ENABLED");
        insertChannel(TENANT_A, "u_ur", "FEISHU", "DISABLED"); // 熔断/停用不投
        // 订阅只勾钉钉
        String dingOnly = deliveryChannelId(TENANT_A, "u_ur", "DINGTALK");
        insertSubscription(TENANT_A, "u_ur", "URG", "[\"INAPP\",\"" + dingOnly + "\"]");
        // NORMAL：仅钉钉 1 行
        assertThatCode0(send(jwt, "dp-ur-1", "URG", "NORMAL", "u_ur"));
        org.assertj.core.api.Assertions.assertThat(deliveryCount(TENANT_A, "MESSAGE", "u_ur")).isEqualTo(1);
        // URGENT：全部 ENABLED（钉钉+企微=2 行，无视矩阵）
        assertThatCode0(send(jwt, "dp-ur-2", "URG", "URGENT", "u_ur"));
        Long urgent = deliveryMapper.selectCount(new LambdaQueryWrapper<NfyaDelivery>()
                .eq(NfyaDelivery::getTenantId, TENANT_A).eq(NfyaDelivery::getUserid, "u_ur")
                .eq(NfyaDelivery::getSourceType, "MESSAGE"));
        org.assertj.core.api.Assertions.assertThat(urgent).isEqualTo(3); // 1(NORMAL) + 2(URGENT)
    }

    @Test
    void announcement_publish_plans_public_and_subscriber_channels() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        // 公共渠道（scope=TENANT, userid=''）
        NfyaChannel pub = new NfyaChannel();
        pub.setTenantId(TENANT_A);
        pub.setScope("TENANT");
        pub.setUserid("");
        pub.setChannelType("DINGTALK");
        pub.setName("公共报警群");
        pub.setTarget("https://oapi.dingtalk.com/robot/send?access_token=pub-1");
        pub.setSecret("");
        pub.setKeyword("");
        pub.setStatus("ENABLED");
        pub.setFailCount(0);
        pub.setExt("{}");
        channelMapper.insert(pub);
        // 订阅 ANNOUNCEMENT 的用户（站外渠道）
        String subCh = insertChannel(TENANT_A, "u_ann", "DINGTALK", "ENABLED");
        insertSubscription(TENANT_A, "u_ann", "ANNOUNCEMENT", "[\"INAPP\",\"" + subCh + "\"]");

        String created = mvc.perform(post("/nfy/api/v1/admin/announcements")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json")
                        .content("{\"title\":\"公告外发\",\"content\":\"c\",\"effective_at\":"
                                + (System.currentTimeMillis() - 1000) + ",\"expire_at\":"
                                + (System.currentTimeMillis() + 86400000L)
                                + ",\"channel_ids\":[\"" + pub.getId() + "\"],\"biz_no\":\"ann-dp-1\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThatCode0(created);
        String annId = extract(created, "announcement_id");
        assertThatCode0(mvc.perform(post("/nfy/api/v1/admin/announcements/" + annId + "/publish")
                        .header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString());

        // 公共渠道 1 行（userid=''）+ 订阅用户 1 行
        Long pubRows = deliveryMapper.selectCount(new LambdaQueryWrapper<NfyaDelivery>()
                .eq(NfyaDelivery::getTenantId, TENANT_A)
                .eq(NfyaDelivery::getSourceType, "ANNOUNCEMENT")
                .eq(NfyaDelivery::getUserid, ""));
        Long subRows = deliveryMapper.selectCount(new LambdaQueryWrapper<NfyaDelivery>()
                .eq(NfyaDelivery::getTenantId, TENANT_A)
                .eq(NfyaDelivery::getSourceType, "ANNOUNCEMENT")
                .eq(NfyaDelivery::getUserid, "u_ann"));
        org.assertj.core.api.Assertions.assertThat(pubRows).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(subRows).isEqualTo(1);
    }

    @Test
    void dlv_query_masked_and_retry_dead_only() throws Exception {
        String jwt = token(TENANT_B, "secret-b");
        insertType(TENANT_B, "DLV", "[\"INAPP\",\"DINGTALK\"]");
        insertChannel(TENANT_B, "u_d", "DINGTALK", "ENABLED");
        assertThatCode0(send(jwt, "dp-dlv", "DLV", null, "u_d"));
        NfyaDelivery row = deliveryMapper.selectOne(new LambdaQueryWrapper<NfyaDelivery>()
                .eq(NfyaDelivery::getTenantId, TENANT_B));
        org.assertj.core.api.Assertions.assertThat(row).isNotNull();
        org.assertj.core.api.Assertions.assertThat(row.getStatus()).isEqualTo("PENDING");
        // DLV-001 查询：target 脱敏
        String list = mvc.perform(get("/nfy/api/v1/admin/deliveries?biz_no=dp-dlv")
                        .header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThatCode0(list);
        org.assertj.core.api.Assertions.assertThat(list)
                .contains("oapi.dingtalk.com").doesNotContain("DINGTALK-u_d").contains("/robot/send?****");
        // DLV-002：非 DEAD 不可重投 10402；DEAD → PENDING
        expectCode(retry(jwt, row.getId()), 10402);
        row.setStatus("DEAD");
        deliveryMapper.updateById(row);
        assertThatCode0(retry(jwt, row.getId()));
        org.assertj.core.api.Assertions.assertThat(deliveryMapper.selectById(row.getId()).getStatus()).isEqualTo("PENDING");
    }

    private String retry(String jwt, Long id) throws Exception {
        return mvc.perform(post("/nfy/api/v1/admin/deliveries/" + id + "/retry")
                        .header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString();
    }

    private String deliveryChannelId(long tenantId, String userid, String type) {
        NfyaChannel c = channelMapper.selectOne(new LambdaQueryWrapper<NfyaChannel>()
                .eq(NfyaChannel::getTenantId, tenantId)
                .eq(NfyaChannel::getUserid, userid)
                .eq(NfyaChannel::getChannelType, type)
                .last("LIMIT 1"));
        return String.valueOf(c.getId());
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
