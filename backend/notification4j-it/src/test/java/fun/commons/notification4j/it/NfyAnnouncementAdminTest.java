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
import java.time.OffsetDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 5b 步：公告管理面（API-AAN-001~005，§4.3/§5.9.2）。
 * VECTOR: TAG=step5-admin
 * 契约：POST 创建 → DRAFT（biz_no 幂等闸 10401；生效窗口非法 10102）；GET 详情全字段；
 * PATCH 限 DRAFT（10402 非草稿态）；publish 草稿→发布（同租户同时生效 ≤20 → 10611，非草稿 10402）；
 * offline 立即下线（重复/已失效 10402，用户侧不可见）；stats {read_count, confirm_count, confirms(Offset)}。
 * 外发任务生成（公共渠道+订阅 ANNOUNCEMENT 站外渠道）在第 6 步接线（已知边界，文档 V1.0.6 登记）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfyAnnouncementAdminTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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
@Tag("step5")
class NfyAnnouncementAdminTest {

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

    private static final long TENANT_A = 88401L;
    private static final long TENANT_B = 88402L;
    private static final long TENANT_C = 88403L;

    @BeforeAll
    void init() throws Exception {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        insertTenant(TENANT_A, "A", "ACTIVE", "secret-a");
        insertTenant(TENANT_B, "B", "ACTIVE", "secret-b");
        insertTenant(TENANT_C, "C", "ACTIVE", "secret-c");
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

    private String createBody(String bizNo, long effPlusHours, long expPlusDays, int needConfirm) {
        String eff = String.valueOf(OffsetDateTime.now().plusHours(effPlusHours).toInstant().toEpochMilli());
        String exp = String.valueOf(OffsetDateTime.now().plusDays(expPlusDays).toInstant().toEpochMilli());
        return "{\"title\":\"标题-" + bizNo + "\",\"content\":\"内容 markdown\",\"level\":\"IMPORTANT\","
                + "\"effective_at\":" + eff + ",\"expire_at\":" + exp + ","
                + "\"need_confirm\":" + needConfirm + ",\"biz_no\":\"" + bizNo + "\"}";
    }

    private String create(String jwt, String body) throws Exception {
        return mvc.perform(post("/nfy/api/v1/admin/announcements")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String patch(String jwt, String id, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/nfy/api/v1/admin/announcements/" + id)
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String action(String jwt, String id, String action) throws Exception {
        return mvc.perform(post("/nfy/api/v1/admin/announcements/" + id + "/" + action)
                        .header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String detail(String jwt, String id) throws Exception {
        return mvc.perform(get("/nfy/api/v1/admin/announcements/" + id)
                        .header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void create_draft_bizno_idempotent_and_window_validation() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        String created = create(jwt, createBody("adm-1", -1, 7, 1));
        assertThatCode0(created);
        org.assertj.core.api.Assertions.assertThat(created).contains("\"status\":\"DRAFT\"").contains("announcement_id");
        String id = extract(created, "announcement_id");

        // biz_no 幂等闸（uk_nfya_ann_tenant_biz_no）→ 10401
        expectCode(create(jwt, createBody("adm-1", -1, 7, 1)), 10401);
        // 生效窗口非法（expire <= effective）→ 10102
        String eff = String.valueOf(OffsetDateTime.now().plusHours(1).toInstant().toEpochMilli());
        expectCode(create(jwt, "{\"title\":\"t\",\"content\":\"c\",\"effective_at\":" + eff
                + ",\"expire_at\":" + eff + ",\"biz_no\":\"adm-bad-win\"}"), 10102);

        // 详情全字段
        String d = detail(jwt, id);
        org.assertj.core.api.Assertions.assertThat(d)
                .contains("\"title\":\"标题-adm-1\"").contains("\"need_confirm\":1")
                .contains("\"status\":\"DRAFT\"").contains("\"level\":\"IMPORTANT\"");

        // PATCH 草稿：改标题 → 详情更新
        assertThatCode0(patch(jwt, id, "{\"title\":\"新标题\"}"));
        org.assertj.core.api.Assertions.assertThat(detail(jwt, id)).contains("新标题");
    }

    @Test
    void publish_patch_after_publish_10402_and_limit_10611() throws Exception {
        // 独立租户：其他用例的已生效公告不占 20 名额
        String jwt = token(TENANT_C, "secret-c");
        String created = create(jwt, createBody("adm-pub", -1, 7, 0));
        String id = extract(created, "announcement_id");

        // 发布 → PUBLISHED；重复发布 → 10402
        assertThatCode0(action(jwt, id, "publish"));
        org.assertj.core.api.Assertions.assertThat(detail(jwt, id)).contains("\"status\":\"PUBLISHED\"");
        expectCode(action(jwt, id, "publish"), 10402);
        // 发布后 PATCH → 10402 非草稿态
        expectCode(patch(jwt, id, "{\"title\":\"迟到的修改\"}"), 10402);

        // 补齐至 20 条同时生效 → 第 21 条发布 10611
        for (int i = 2; i <= 20; i++) {
            String c = create(jwt, createBody("adm-fill-" + i, -1, 7, 0));
            expectCode(action(jwt, extract(c, "announcement_id"), "publish"), 0);
        }
        String extra = create(jwt, createBody("adm-extra", -1, 7, 0));
        expectCode(action(jwt, extract(extra, "announcement_id"), "publish"), 10611);
        // 超限后先下线一条 → 可再发布
        assertThatCode0(action(jwt, id, "offline"));
        assertThatCode0(action(jwt, extract(extra, "announcement_id"), "publish"));
    }

    @Test
    void offline_invisible_at_runtime_and_read_rejected() throws Exception {
        String jwtA = token(TENANT_A, "secret-a");
        String jwtB = token(TENANT_B, "secret-b");
        String id = extract(create(jwtA, createBody("adm-off", -1, 7, 1)), "announcement_id");
        assertThatCode0(action(jwtA, id, "publish"));

        // B 不可见 A 的公告；A 下线后自身也不可见
        expectCode(action(jwtB, id, "offline"), 10400);
        org.assertj.core.api.Assertions.assertThat(runtimeList(jwtA)).contains("标题-adm-off");
        assertThatCode0(action(jwtA, id, "offline"));
        org.assertj.core.api.Assertions.assertThat(runtimeList(jwtA)).doesNotContain("标题-adm-off");
        // 重复下线 → 10402；下线后读侧回执 → 10400 未生效
        expectCode(action(jwtA, id, "offline"), 10402);
        expectCode(mvc.perform(post("/nfy/api/v1/runtime/announcements/" + id + "/read")
                        .header("Authorization", "Bearer " + jwtA).header("X-User-Id", "u_1"))
                .andReturn().getResponse().getContentAsString(), 10400);
    }

    @Test
    void stats_counts_reads_confirms_with_offset() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        String id = extract(create(jwt, createBody("adm-stat", -1, 7, 1)), "announcement_id");
        assertThatCode0(action(jwt, id, "publish"));
        // 两个用户均读+确认（s_u1 先、s_u2 后）
        for (String[] ru : new String[][]{{"s_u1", "confirm"}, {"s_u2", "confirm"}}) {
            mvc.perform(post("/nfy/api/v1/runtime/announcements/" + id + "/read")
                            .header("Authorization", "Bearer " + jwt).header("X-User-Id", ru[0]))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
            mvc.perform(post("/nfy/api/v1/runtime/announcements/" + id + "/" + ru[1])
                            .header("Authorization", "Bearer " + jwt).header("X-User-Id", ru[0]))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        }
        String stats = mvc.perform(get("/nfy/api/v1/admin/announcements/" + id + "/stats")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThatCode0(stats);
        org.assertj.core.api.Assertions.assertThat(stats)
                .contains("\"read_count\":\"2\"").contains("\"confirm_count\":\"2\"")
                .contains("\"userid\":\"s_u1\"").contains("\"userid\":\"s_u2\"").contains("confirm_at");

        // Offset 分页：confirm_at 倒序，第 2 页（offset=1&limit=1）应为先确认的 s_u1
        String page2 = mvc.perform(get("/nfy/api/v1/admin/announcements/" + id + "/stats?offset=1&limit=1")
                        .header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(page2).contains("\"userid\":\"s_u1\"");
    }

    @Test
    void admin_list_filter_and_cross_tenant_10400() throws Exception {
        String jwtA = token(TENANT_A, "secret-a");
        String jwtB = token(TENANT_B, "secret-b");
        create(jwtA, createBody("adm-list-1", -1, 7, 0));
        // 列表含状态过滤
        String drafts = mvc.perform(get("/nfy/api/v1/admin/announcements?status=DRAFT")
                        .header("Authorization", "Bearer " + jwtA))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(drafts).contains("标题-adm-list-1");
        // B 访问 A 的公告详情 → 10400
        String id = extract(create(jwtA, createBody("adm-list-2", -1, 7, 0)), "announcement_id");
        expectCode(mvc.perform(get("/nfy/api/v1/admin/announcements/" + id)
                        .header("Authorization", "Bearer " + jwtB))
                .andReturn().getResponse().getContentAsString(), 10400);
    }

    private String runtimeList(String jwt) throws Exception {
        return mvc.perform(get("/nfy/api/v1/runtime/announcements")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_1"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 平台身份换 token（client_id 必须是配置的平台字面量；同 NfySmokeTest 口径） */
    private String platformToken() throws Exception {
        String body = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=PLATFORM&client_secret=platform-secret-IT"))
                .andReturn().getResponse().getContentAsString();
        if (!body.contains("access_token")) {
            throw new IllegalStateException("平台换 token 失败: " + body);
        }
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    /** POST 助手：userid 非空时附带 X-User-Id（runtime 端点守卫 10101），平台端点传 null */
    private String postBearer(String jwt, String url, String userid, String body) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder =
                post(url).header("Authorization", "Bearer " + jwt)
                        .contentType("application/json").content(body == null ? "{}" : body);
        if (userid != null) {
            builder = builder.header("X-User-Id", userid);
        }
        return mvc.perform(builder)
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void platform_announcement_confirm_receipt_lands_in_platform_domain() throws Exception {
        // 第 28 步口径修复回归（原第 27 步冒烟发现的跨域双口径缺陷）：租户用户确认平台公告，
        // 回执行落公告归属域（tenant_id=0）→ PAN detail/stats 的 confirm_count（按公告归属域数回执）可见。
        String plat = platformToken();
        String jwt = token(TENANT_A, "secret-a");
        long eff = OffsetDateTime.now().minusHours(1).toInstant().toEpochMilli();
        long exp = OffsetDateTime.now().plusDays(7).toInstant().toEpochMilli();
        String created = postBearer(plat, "/nfy/platform/api/v1/announcements", null,
                "{\"title\":\"PAN-确认域回归\",\"content\":\"c\",\"effective_at\":" + eff
                        + ",\"expire_at\":" + exp + ",\"need_confirm\":1,\"biz_no\":\"pan-confirm-1\"}");
        assertThatCode0(created);
        String id = extract(created, "announcement_id");
        assertThatCode0(postBearer(plat, "/nfy/platform/api/v1/announcements/" + id + "/publish", null, null));

        // ⓪ 用户侧口径基线：本公告在未确认集（unconfirmed_count ≥1；同租户可能有其他生效需确认公告）
        String beforeBody = mvc.perform(get("/nfy/api/v1/runtime/messages/unread-count")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_1"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher bm = java.util.regex.Pattern
                .compile("\"unconfirmed_count\":\"?(\\d+)\"?").matcher(beforeBody);
        org.assertj.core.api.Assertions.assertThat(bm.find()).isTrue();
        int beforeCount = Integer.parseInt(bm.group(1));
        org.assertj.core.api.Assertions.assertThat(beforeCount).isGreaterThanOrEqualTo(1);

        // 租户用户 A 标记阅读 + 确认
        assertThatCode0(postBearer(jwt, "/nfy/api/v1/runtime/announcements/" + id + "/read", "u_1", null));
        assertThatCode0(postBearer(jwt, "/nfy/api/v1/runtime/announcements/" + id + "/confirm", "u_1", null));

        // ② 确认后：my_status CONFIRMED + unconfirmed_count 恰减 1（回执落 0 域仍被用户侧 IN (0, 本租户) 双域反查扣减）
        org.assertj.core.api.Assertions.assertThat(runtimeList(jwt))
                .contains("PAN-确认域回归").contains("\"my_status\":\"CONFIRMED\"");
        mvc.perform(get("/nfy/api/v1/runtime/messages/unread-count")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unconfirmed_count").value(beforeCount - 1));

        // ③ PAN 管理侧口径：detail confirm_count=1（修前恒 0）；detail 的回执实数与公告冗余列自洽
        String detail = mvc.perform(get("/nfy/platform/api/v1/announcements/" + id)
                        .header("Authorization", "Bearer " + plat))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThatCode0(detail);
        org.assertj.core.api.Assertions.assertThat(detail)
                .contains("\"need_confirm\":1").contains("\"confirm_count\":\"1\"");

        // ④ 租户 admin 面访问平台公告 → 10400（公告归属 0 域，防探测口径不变）
        expectCode(detail(jwt, id), 10400);
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
