package fun.commons.notification4j.it;

import fun.commons.notification4j.entity.NfyaAnnouncement;
import fun.commons.notification4j.entity.NfyaTenant;
import fun.commons.notification4j.mapper.NfyaAnnouncementMapper;
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
 * 第 5a 步：公告域 runtime 面（API-ANN-001/002/003，§5.6）。
 * VECTOR: TAG=step5-announcement
 * 契约：生效列表 = 平台公告(tenant_id=0) + 本租户生效公告合并，published_at 倒序，
 * 仅 status=PUBLISHED 且 now∈[effective_at, expire_at)；
 * my_status NONE/READ/CONFIRMED；标记阅读幂等（uk 兜底）；
 * 确认幂等 uk_nfya_ann_read_user；10400 公告不存在/未生效；10402 公告已失效。
 * 管理面（AAN-001~005 创建/发布/下线/统计）在第 5b 步；未读数并入门槛在第 3 步评审修复轮接线。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfyAnnouncementFlowTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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
class NfyAnnouncementFlowTest {

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
    @Autowired NfyaAnnouncementMapper announcementMapper;

    private static final long TENANT_A = 88301L;
    private static final long TENANT_B = 88302L;
    private static final long PLATFORM = 0L;

    @BeforeAll
    void init() throws Exception {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        insertTenant(TENANT_A, "A", "ACTIVE", "secret-a");
        insertTenant(TENANT_B, "B", "ACTIVE", "secret-b");
        // 平台公告（生效中，需确认）
        insertAnnouncement(PLATFORM, "plat-1", "PLATFORM", "平台维护通知", "IMPORTANT", 1,
                "PUBLISHED", OffsetDateTime.now().minusHours(2), OffsetDateTime.now().plusDays(7));
        // A 租户公告（生效中，无需确认）
        insertAnnouncement(TENANT_A, "a-1", "TENANT", "A 系统上线公告", "NORMAL", 0,
                "PUBLISHED", OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusDays(3));
        // A 租户草稿（不可见）
        insertAnnouncement(TENANT_A, "a-draft", "TENANT", "A 草稿", "NORMAL", 0,
                "DRAFT", OffsetDateTime.now().plusHours(1), OffsetDateTime.now().plusDays(3));
        // A 租户已过期（不可见）
        insertAnnouncement(TENANT_A, "a-exp", "TENANT", "A 过期公告", "NORMAL", 0,
                "PUBLISHED", OffsetDateTime.now().minusDays(5), OffsetDateTime.now().minusDays(1));
    }

    private void insertTenant(long id, String name, String status, String secret) {
        NfyaTenant t = new NfyaTenant();
        t.setId(id);
        t.setName(name);
        t.setStatus(status);
        t.setTenantSecret(secret);
        tenantMapper.insert(t);
    }

    private void insertAnnouncement(long tenantId, String bizNo, String scope, String title, String level,
                                    int needConfirm, String status, OffsetDateTime effectiveAt, OffsetDateTime expireAt) {
        NfyaAnnouncement a = new NfyaAnnouncement();
        a.setTenantId(tenantId);
        a.setBizNo(bizNo);
        a.setScope(scope);
        a.setTitle(title);
        a.setContent("内容-" + title);
        a.setLevel(level);
        a.setNeedConfirm(needConfirm);
        a.setLinkUrl("");
        a.setChannelIds("[]");
        a.setStatus(status);
        a.setEffectiveAt(effectiveAt);
        a.setExpireAt(expireAt);
        a.setPublishedAt("PUBLISHED".equals(status) ? effectiveAt : null);
        a.setConfirmCount(0);
        a.setExt("{}");
        announcementMapper.insert(a);
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

    private String announcementId(String jwt, String title) throws Exception {
        String list = mvc.perform(get("/nfy/api/v1/runtime/announcements")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_1"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"announcement_id\":\"(\\d+)\",[^}]*?\"title\":\"" + title + "\"").matcher(list);
        org.assertj.core.api.Assertions.assertThat(m.find()).as("列表含 %s: %s", title, list).isTrue();
        return m.group(1);
    }

    @Test
    void effective_list_merges_platform_and_tenant_sorted() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        String list = mvc.perform(get("/nfy/api/v1/runtime/announcements")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_1"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        // 生效中两条都可见，草稿/过期不可见；published_at 倒序（租户公告 1h 前 > 平台 2h 前 → 租户在前）
        org.assertj.core.api.Assertions.assertThat(list)
                .contains("平台维护通知").contains("A 系统上线公告")
                .doesNotContain("A 草稿").doesNotContain("A 过期公告")
                .contains("\"my_status\":\"NONE\"").contains("\"need_confirm\":1");
        int platIdx = list.indexOf("平台维护通知");
        int tenantIdx = list.indexOf("A 系统上线公告");
        org.assertj.core.api.Assertions.assertThat(tenantIdx).isLessThan(platIdx);
    }

    @Test
    void mark_read_then_confirm_idempotent() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        String id = announcementId(jwt, "平台维护通知");

        // 标记阅读 → my_status READ；重复标记幂等
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/nfy/api/v1/runtime/announcements/" + id + "/read")
                            .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        }
        String list = mvc.perform(get("/nfy/api/v1/runtime/announcements")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_1"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(list).contains("\"my_status\":\"READ\"");

        // 确认 → CONFIRMED；重复确认幂等（uk 兜底）
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/nfy/api/v1/runtime/announcements/" + id + "/confirm")
                            .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.confirmed").value(true));
        }
        list = mvc.perform(get("/nfy/api/v1/runtime/announcements")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_1"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(list).contains("\"my_status\":\"CONFIRMED\"");

        // 未读数合成（§5.3）：确认后 unconfirmed_count 归零（平台 plat-1 是唯一 need_confirm=1 生效公告）
        mvc.perform(get("/nfy/api/v1/runtime/messages/unread-count")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unconfirmed_count").value(0));
    }

    @Test
    void confirm_or_read_nonexistent_10400_expired_10402() throws Exception {
        String jwt = token(TENANT_A, "secret-a");
        // 不存在 → 10400
        mvc.perform(post("/nfy/api/v1/runtime/announcements/999999/confirm")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10400));
        // 已过期（不在生效窗口）→ 10402（对 runtime 面不可见公告：先 read 同样拒）
        String expId = announcementIdRaw("a-exp");
        mvc.perform(post("/nfy/api/v1/runtime/announcements/" + expId + "/read")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", "u_1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10402));
    }

    @Test
    void cross_tenant_sees_platform_only() throws Exception {
        String jwtB = token(TENANT_B, "secret-b");
        String list = mvc.perform(get("/nfy/api/v1/runtime/announcements")
                        .header("Authorization", "Bearer " + jwtB).header("X-User-Id", "u_1"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(list)
                .contains("平台维护通知").doesNotContain("A 系统上线公告");

        // B 对 A 的公告确认 → 10400（该公告不在 B 的可见集）
        String aId = announcementIdRaw("a-1");
        mvc.perform(post("/nfy/api/v1/runtime/announcements/" + aId + "/confirm")
                        .header("Authorization", "Bearer " + jwtB).header("X-User-Id", "u_1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10400));
    }

    /** 绕过可见集直接查 id（供负路径用例） */
    private String announcementIdRaw(String bizNo) {
        NfyaAnnouncement a = announcementMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<NfyaAnnouncement>()
                        .eq(NfyaAnnouncement::getBizNo, bizNo));
        org.assertj.core.api.Assertions.assertThat(a).isNotNull();
        return String.valueOf(a.getId());
    }
}
