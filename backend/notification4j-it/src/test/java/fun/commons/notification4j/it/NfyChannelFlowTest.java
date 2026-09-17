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
import fun.commons.notification4j.entity.NfyaTenant;
import fun.commons.notification4j.mapper.NfyaTenantMapper;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 4a 步：渠道域（注册 SSRF 白名单 / 列表脱敏 / 验证业务码判定 / 启停 / 删除）。
 * VECTOR: TAG=step4-channel
 * 契约（接口设计文档 §5.7/§5.9.1）：PENDING 落库不发验证消息（性能预算 P99≤300ms）；
 * SSRF：https + 官方域名白名单 + DNS 禁内网段 → 10609；同 target 重复 → 10401；
 * 同类型渠道 ≥5 → 10605；验证成功判定 = HTTP 2xx 且业务码成功（errcode=0/code=0，
 * HTTP 200 业务失败也判失败）→ ENABLED；失败 10604 带渠道返回摘要；
 * 未验证渠道 PATCH ENABLED → 10610；删除后操作 10400；跨租户/跨用户隔离 10400。
 *
 * 已知边界（评审登记）：EMAIL 验证依赖第 6 步 SMTP 适配器，本步返回 10604；
 * 删除渠道的订阅剔除在第 4b 步（订阅矩阵）接入；Idempotency-Key 以 UNIQUE 约束为真闸。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfyChannelFlowTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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
@Tag("step4")
class NfyChannelFlowTest {

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

        // SSRF 校验的 DNS 解析替换为离线确定性实现(白名单域名 → 公网 IP; 未知域名 → 解析失败)
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
    @Autowired
    fun.commons.notification4j.service.ChannelVerifier channelVerifier;

    private static final long TENANT_A = 88101L;
    private static final long TENANT_B = 88102L;

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

    /** 每个测试独立 userid：渠道上限(同租户同用户同类型≤5)按用户计数，避免用例间状态污染 */
    private String register(String jwt, String userid, String body) throws Exception {
        return mvc.perform(post("/nfy/api/v1/runtime/channels")
                        .header("Authorization", "Bearer " + jwt)
                        .header("X-User-Id", userid)
                        .contentType("application/json")
                        .content(body))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String dingTalkBody(String token) {
        return "{\"channel_type\":\"DINGTALK\",\"name\":\"SRE 报警群\","
                + "\"target\":\"https://oapi.dingtalk.com/robot/send?access_token=" + token + "\","
                + "\"secret\":\"SECxxxxxxxx\",\"keyword\":\"通知\"}";
    }

    @Test
    void register_success_pending_and_list_masked_target() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        String body = register(jwt, "u_reg", dingTalkBody("abcd1234"));
        assertThatCode0(body);
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("\"status\":\"PENDING\"").contains("channel_id").contains("verify_tip");

        // 列表脱敏：webhook token 段不可见
        String list = mvc.perform(get("/nfy/api/v1/runtime/channels")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_reg"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(list)
                .contains("oapi.dingtalk.com").doesNotContain("abcd1234")
                .contains("\"fail_count\":0");
    }

    @Test
    void register_target_validation_ssrf_and_params() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        // http 明文 → 10609
        expectCode(register(jwt, "u_tv", "{\"channel_type\":\"DINGTALK\",\"name\":\"n1\","
                + "\"target\":\"http://oapi.dingtalk.com/robot/send?access_token=x\"}"), 10609);
        // 非白名单域名 → 10609
        expectCode(register(jwt, "u_tv", "{\"channel_type\":\"DINGTALK\",\"name\":\"n2\","
                + "\"target\":\"https://evil.example.com/robot/send\"}"), 10609);
        // 内网地址 → 10609
        expectCode(register(jwt, "u_tv", "{\"channel_type\":\"DINGTALK\",\"name\":\"n3\","
                + "\"target\":\"https://192.168.1.1/robot/send\"}"), 10609);
        // 白名单域名的非 443 端口 → 10609
        expectCode(register(jwt, "u_tv", "{\"channel_type\":\"DINGTALK\",\"name\":\"n3b\","
                + "\"target\":\"https://oapi.dingtalk.com:8443/robot/send\"}"), 10609);
        // EMAIL 格式非法 → 10100（零 DB 判定归 101xx，§6.1）
        expectCode(register(jwt, "u_tv", "{\"channel_type\":\"EMAIL\",\"name\":\"n4\","
                + "\"target\":\"not-an-email\"}"), 10100);
        // 枚举非法 → 10100
        expectCode(register(jwt, "u_tv", "{\"channel_type\":\"SMS\",\"name\":\"n5\","
                + "\"target\":\"https://oapi.dingtalk.com/x\"}"), 10100);
        // 名称超长 → 10100（@Valid → fwk4j-web 统一处理）
        expectCode(register(jwt, "u_tv", "{\"channel_type\":\"DINGTALK\",\"name\":\"" + "长".repeat(31) + "\","
                + "\"target\":\"https://oapi.dingtalk.com/x\"}"), 10100);
        // 合法 EMAIL 注册成功
        assertThatCode0(register(jwt, "u_tv", "{\"channel_type\":\"EMAIL\",\"name\":\"邮箱\","
                + "\"target\":\"ops@example.com\"}"));
    }

    @Test
    void duplicate_target_10401_and_type_limit_10605() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        assertThatCode0(register(jwt, "u_dup", dingTalkBody("dup-1")));
        // 同租户同用户同类型同 target → 10401
        expectCode(register(jwt, "u_dup", dingTalkBody("dup-1")), 10401);
        // 同类型第 6 个 → 10605
        for (int i = 2; i <= 5; i++) {
            assertThatCode0(register(jwt, "u_dup", dingTalkBody("tok-" + i)));
        }
        expectCode(register(jwt, "u_dup", dingTalkBody("tok-6")), 10605);
        // 其他类型不受限
        assertThatCode0(register(jwt, "u_dup", "{\"channel_type\":\"WECOM\",\"name\":\"企微\","
                + "\"target\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=w1\"}"));
    }

    @Test
    void verify_success_business_code_zero_enables() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        String body = register(jwt, "u_vs", dingTalkBody("verify-ok"));
        String channelId = extract(body, "channel_id");
        org.springframework.test.web.client.MockRestServiceServer server =
                org.springframework.test.web.client.MockRestServiceServer.bindTo(channelVerifier.restTemplate()).build();
        // 加签渠道：query 携带 timestamp/sign；业务码 errcode=0 → 成功
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.containsString("timestamp=")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.containsString("sign=")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(
                        org.springframework.http.HttpMethod.POST))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withSuccess("{\"errcode\":0,\"errmsg\":\"ok\"}", org.springframework.http.MediaType.APPLICATION_JSON));
        String verified = mvc.perform(post("/nfy/api/v1/runtime/channels/" + channelId + "/verify")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_vs"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThatCode0(verified);
        org.assertj.core.api.Assertions.assertThat(verified).contains("\"status\":\"ENABLED\"").contains("last_verify_at");
        server.verify();

        // 列表状态 ENABLED；PATCH 改名 + 启停合法流转
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/nfy/api/v1/runtime/channels/" + channelId)
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_vs")
                        .contentType("application/json").content("{\"name\":\"改名后\",\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/nfy/api/v1/runtime/channels/" + channelId)
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_vs")
                        .contentType("application/json").content("{\"status\":\"ENABLED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void verify_http200_business_fail_is_10604() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        String channelId = extract(register(jwt, "u_vf", dingTalkBody("verify-bad")), "channel_id");
        org.springframework.test.web.client.MockRestServiceServer server =
                org.springframework.test.web.client.MockRestServiceServer.bindTo(channelVerifier.restTemplate()).build();
        // HTTP 200 但业务码失败（关键词不匹配）→ 10604 且带摘要
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withSuccess("{\"errcode\":310000,\"errmsg\":\"keywords not in content\"}",
                                org.springframework.http.MediaType.APPLICATION_JSON));
        String failed = mvc.perform(post("/nfy/api/v1/runtime/channels/" + channelId + "/verify")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_vf"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        expectCode(failed, 10604);
        org.assertj.core.api.Assertions.assertThat(failed).contains("310000");
        server.verify();

        // 状态仍 PENDING
        String list = mvc.perform(get("/nfy/api/v1/runtime/channels")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_vf"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(list).contains("\"status\":\"PENDING\"");
    }

    @Test
    void patch_pending_enable_rejected_10610_and_delete() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        String channelId = extract(register(jwt, "u_pp", dingTalkBody("pending-en")), "channel_id");
        // 未验证 → ENABLED 拒绝 10610
        expectCode(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/nfy/api/v1/runtime/channels/" + channelId)
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_pp")
                        .contentType("application/json").content("{\"status\":\"ENABLED\"}"))
                .andReturn().getResponse().getContentAsString(), 10610);
        // 删除 → 后续操作 10400（防探测同一码）
        assertThatCode0(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/nfy/api/v1/runtime/channels/" + channelId)
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_pp"))
                .andReturn().getResponse().getContentAsString());
        expectCode(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/nfy/api/v1/runtime/channels/" + channelId)
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_pp"))
                .andReturn().getResponse().getContentAsString(), 10400);
    }

    @Test
    void cross_tenant_and_cross_user_isolation() throws Exception {
        String jwtA = token(TENANT_A, "secret-a");
        String jwtB = token(TENANT_B, "secret-b");
        String channelId = extract(register(jwtA, "u_iso", dingTalkBody("iso-ch")), "channel_id");
        // B 租户 token + A 的 channel_id → 10400（防探测同码）
        expectCode(mvc.perform(post("/nfy/api/v1/runtime/channels/" + channelId + "/verify")
                        .header("Authorization", "Bearer " + jwtB).header("X-User-Id", "u_iso"))
                .andReturn().getResponse().getContentAsString(), 10400);
        // 同租户不同用户 → 10400
        expectCode(mvc.perform(post("/nfy/api/v1/runtime/channels/" + channelId + "/verify")
                        .header("Authorization", "Bearer " + jwtA).header("X-User-Id", "u_other"))
                .andReturn().getResponse().getContentAsString(), 10400);
        // B 的渠道列表不含 A 的渠道
        String listB = mvc.perform(get("/nfy/api/v1/runtime/channels")
                        .header("Authorization", "Bearer " + jwtB).header("X-User-Id", "u_1b"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(listB).doesNotContain("iso-ch");
    }

    @Test
    void email_verify_deferred_10604() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        String channelId = extract(register(jwt, "u_em", "{\"channel_type\":\"EMAIL\",\"name\":\"邮箱渠道\","
                + "\"target\":\"ops@example.com\"}"), "channel_id");
        expectCode(mvc.perform(post("/nfy/api/v1/runtime/channels/" + channelId + "/verify")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_em"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), 10604);
    }

    @Test
    void email_pending_patch_enabled_succeeds_nd_l5_01() throws Exception {
        // ND-L5-01 修复回归：EMAIL 渠道纯 API 通路可达 ENABLED——PENDING 直启（豁免 last_verify_at 前置，
        // 豁免依据=ChannelVerifier 10604 文案契约「首次投递时校验」）；IM 未验证直启仍 10610
        // （patch_pending_enable_rejected_10610_and_delete 已钉）。
        String jwt = token(TENANT_A, "secret-a");
        String channelId = extract(register(jwt, "u_em_en", "{\"channel_type\":\"EMAIL\",\"name\":\"邮箱直启\","
                + "\"target\":\"direct-enable@example.com\"}"), "channel_id");
        String patched = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/nfy/api/v1/runtime/channels/" + channelId)
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_em_en")
                        .contentType("application/json").content("{\"status\":\"ENABLED\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThatCode0(patched);
        org.assertj.core.api.Assertions.assertThat(patched).contains("\"status\":\"ENABLED\"");
        // 列表复核：状态真落库（后续投递计划按 ENABLED 过滤即可达，外发链闭合）
        String list = mvc.perform(get("/nfy/api/v1/runtime/channels")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_em_en"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(list).contains("\"status\":\"ENABLED\"");
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

    @Test
    void verify_wecom_keyword_prefix_and_echo() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        String body = register(jwt, "u_kw", "{\"channel_type\":\"WECOM\",\"name\":\"企微kw\",\"keyword\":\"报警\","
                + "\"target\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=wkw\"}");
        String channelId = extract(body, "channel_id");
        org.springframework.test.web.client.MockRestServiceServer server =
                org.springframework.test.web.client.MockRestServiceServer.bindTo(channelVerifier.restTemplate()).build();
        // 企微无加签；验证文案必须携带用户关键词（否则关键词机器人必拒）
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .jsonPath("$.text.content", org.hamcrest.Matchers.containsString("报警")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withSuccess("{\"errcode\":0,\"errmsg\":\"ok\"}", org.springframework.http.MediaType.APPLICATION_JSON));
        String resp = mvc.perform(post("/nfy/api/v1/runtime/channels/" + channelId + "/verify")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_kw"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThatCode0(resp);
        org.assertj.core.api.Assertions.assertThat(resp).contains("\"status\":\"ENABLED\"");
        server.verify();
    }

    @Test
    void verify_feishu_sign_fields_and_path_token_masked() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        String body = register(jwt, "u_fs", "{\"channel_type\":\"FEISHU\",\"name\":\"飞书群\","
                + "\"secret\":\"FSSECRET\","
                + "\"target\":\"https://open.feishu.cn/open-apis/bot/v2/hook/feishu-token-xyz\"}");
        String channelId = extract(body, "channel_id");
        org.springframework.test.web.client.MockRestServiceServer server =
                org.springframework.test.web.client.MockRestServiceServer.bindTo(channelVerifier.restTemplate()).build();
        // 飞书加签在 body：timestamp(秒) + sign；业务码 code=0
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .jsonPath("$.timestamp").exists())
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .jsonPath("$.sign").exists())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withSuccess("{\"code\":0,\"msg\":\"success\"}", org.springframework.http.MediaType.APPLICATION_JSON));
        assertThatCode0(mvc.perform(post("/nfy/api/v1/runtime/channels/" + channelId + "/verify")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_fs"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        server.verify();
        // 列表脱敏：path 末段 token 遮蔽
        String list = mvc.perform(get("/nfy/api/v1/runtime/channels")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_fs"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(list)
                .contains("/open-apis/bot/v2/hook/****")
                .doesNotContain("feishu-token-xyz");
    }

    @Test
    void verify_http_500_is_10604_with_http_summary() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        String channelId = extract(register(jwt, "u_5xx", dingTalkBody("err-5xx")), "channel_id");
        org.springframework.test.web.client.MockRestServiceServer server =
                org.springframework.test.web.client.MockRestServiceServer.bindTo(channelVerifier.restTemplate()).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withServerError());
        String failed = mvc.perform(post("/nfy/api/v1/runtime/channels/" + channelId + "/verify")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_5xx"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        expectCode(failed, 10604);
        org.assertj.core.api.Assertions.assertThat(failed).contains("HTTP 500");
        server.verify();
    }

    private void assertThatCode0(String body) {
        org.assertj.core.api.Assertions.assertThat(body).contains("\"code\":0").doesNotContain("\"fail\":true");
    }
}
