package fun.commons.notification4j.it;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import fun.commons.notification4j.entity.NfyaMessageType;
import fun.commons.notification4j.entity.NfyaTenant;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 8a 步：平台域（API-PTE-005 强制订阅 + API-PAN-001~004 平台公告，§4.4）。
 * VECTOR: TAG=step8-platform
 * 契约：PLATFORM token（合成租户 tenant_id=0）+ @PlatformDomain 型别隔离（打租户域 403）；
 * PTE-005 {type_code, mandatory} 为 mandatory 唯一设置入口（10400 类型/租户不存在，10100 枚举）；
 * PAN 与 AAN 同构但 tenant_id=0/scope=PLATFORM/channel_ids 恒空（平台域无渠道资源，
 * 站外仅走用户订阅 ANNOUNCEMENT 路径——见 delivery 计划 6a）；publish/offline/PATCH 限草稿同构；
 * 10611 同租户（平台=tenant 0）未失效 ≤20 独立计数。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfyPlatformDomainTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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
@Tag("step8")
class NfyPlatformDomainTest {

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

    private static final long TENANT_A = 88701L;
    private String openIdA;

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
        openIdA = fun.commons.framework4j.id.util.IdObfuscator.toOpenId(TENANT_A);

        NfyaMessageType type = new NfyaMessageType();
        type.setTenantId(TENANT_A);
        type.setTypeCode("PTE_T1");
        type.setName("类型1");
        type.setDefaultLevel("NORMAL");
        type.setDefaultChannels("[\"INAPP\"]");
        type.setMandatory(0);
        type.setBuiltIn(0);
        type.setStatus("ENABLED");
        type.setExt("{}");
        typeMapper.insert(type);
    }

    private String platformToken() throws Exception {
        String body = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=PLATFORM&client_secret=platform-secret-IT"))
                .andReturn().getResponse().getContentAsString();
        if (!body.contains("access_token")) {
            throw new IllegalStateException("换平台 token 失败: " + body);
        }
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    private String tenantToken() throws Exception {
        String body = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=" + TENANT_A + "&client_secret=secret-a"))
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    private String pPost(String jwt, String url, String body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + jwt)
                        .contentType("application/json").content(body == null ? "{}" : body))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String pGet(String jwt, String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void platform_token_type_isolation_and_tenant_token_rejected() throws Exception {
        String plat = platformToken();
        // PLATFORM token 打租户域 → DomainGuard：HTTP 200 信封 code=403「租户域需要真实租户身份」（第 2 步口径）
        String tenantSide = mvc.perform(get("/nfy/api/v1/admin/types").header("Authorization", "Bearer " + plat))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(tenantSide)
                .contains("\"code\":403").contains("租户身份");
        // TENANT token 打平台域 → DomainGuard 拒（平台域需要平台身份口径）
        String tenant = tenantToken();
        String platformSide = mvc.perform(get("/nfy/platform/api/v1/announcements").header("Authorization", "Bearer " + tenant))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(platformSide).contains("\"code\":403");
    }

    @Test
    void pte005_sets_mandatory_and_validates() throws Exception {
        String plat = platformToken();
        String url = "/nfy/platform/api/v1/tenants/" + openIdA + "/type-mandatory";
        // mandatory:2 → 10100
        expectCode(pPost(plat, url, "{\"type_code\":\"PTE_T1\",\"mandatory\":2}"), 10100);
        // 未知 open_id → 10400
        expectCode(pPost(plat, "/nfy/platform/api/v1/tenants/NOPE123/type-mandatory",
                "{\"type_code\":\"PTE_T1\",\"mandatory\":1}"), 10400);
        // 正常设置 → 落库 mandatory=1（订阅强制校验在订阅 IT 已覆盖，此处验设置通路）
        assertThatCode0(pPost(plat, url, "{\"type_code\":\"PTE_T1\",\"mandatory\":1}"));
        NfyaMessageType t = typeMapper.selectOne(new LambdaQueryWrapper<NfyaMessageType>()
                .eq(NfyaMessageType::getTenantId, TENANT_A)
                .eq(NfyaMessageType::getTypeCode, "PTE_T1"));
        org.assertj.core.api.Assertions.assertThat(t.getMandatory()).isEqualTo(1);
        // 复位 0
        assertThatCode0(pPost(plat, url, "{\"type_code\":\"PTE_T1\",\"mandatory\":0}"));
    }

    @Test
    void pan_create_publish_visible_to_all_tenants_and_channel_ids_cleared() throws Exception {
        String plat = platformToken();
        String eff = String.valueOf(System.currentTimeMillis() - 1000);
        String exp = String.valueOf(System.currentTimeMillis() + 86400000L);
        // 提交 channel_ids 非空 → 服务端强制清空（平台域无渠道资源）
        String created = pPost(plat, "/nfy/platform/api/v1/announcements",
                "{\"title\":\"平台公告P1\",\"content\":\"c\",\"effective_at\":" + eff + ",\"expire_at\":" + exp
                        + ",\"need_confirm\":1,\"channel_ids\":[\"999\"],\"biz_no\":\"pan-1\"}");
        assertThatCode0(created);
        org.assertj.core.api.Assertions.assertThat(created).contains("\"status\":\"DRAFT\"");
        String id = extract(created, "announcement_id");

        // 详情：channel_ids 恒空
        String detail = pGet(plat, "/nfy/platform/api/v1/announcements/" + id);
        assertThatCode0(detail);
        org.assertj.core.api.Assertions.assertThat(detail).contains("\"channel_ids\":[]");

        // publish → 两租户 runtime 均可见（tenant_id=0 全租户）
        assertThatCode0(pPost(plat, "/nfy/platform/api/v1/announcements/" + id + "/publish", null));
        String tenantJwt = tenantToken();
        String runtimeList = mvc.perform(get("/nfy/api/v1/runtime/announcements")
                        .header("Authorization", "Bearer " + tenantJwt).header("X-User-Id", "u_1"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(runtimeList).contains("平台公告P1");

        // 已发布 PATCH → 10402；重复 publish → 10402
        expectCode(pPost(plat, "/nfy/platform/api/v1/announcements/" + id + "/publish", null), 10402);
        expectCode(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/nfy/platform/api/v1/announcements/" + id)
                        .header("Authorization", "Bearer " + plat)
                        .contentType("application/json").content("{\"title\":\"迟到\"}"))
                .andReturn().getResponse().getContentAsString(), 10402);

        // offline → runtime 不可见；重复 offline → 10402
        assertThatCode0(pPost(plat, "/nfy/platform/api/v1/announcements/" + id + "/offline", null));
        String after = mvc.perform(get("/nfy/api/v1/runtime/announcements")
                        .header("Authorization", "Bearer " + tenantJwt).header("X-User-Id", "u_1"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(after).doesNotContain("平台公告P1");
        expectCode(pPost(plat, "/nfy/platform/api/v1/announcements/" + id + "/offline", null), 10402);
    }

    @Test
    void pan_list_and_10611_platform_quota_independent() throws Exception {
        String plat = platformToken();
        // 租户侧已发布 1 条（前用例 offline 了平台公告，租户侧配额不受影响）
        // 平台侧补满 20 条生效 → 第 21 条 10611
        String eff = String.valueOf(System.currentTimeMillis() - 1000);
        String exp = String.valueOf(System.currentTimeMillis() + 86400000L);
        for (int i = 1; i <= 20; i++) {
            String c = pPost(plat, "/nfy/platform/api/v1/announcements",
                    "{\"title\":\"配额" + i + "\",\"content\":\"c\",\"effective_at\":" + eff + ",\"expire_at\":" + exp
                            + ",\"biz_no\":\"pan-q-" + i + "\"}");
            expectCode(pPost(plat, "/nfy/platform/api/v1/announcements/" + extract(c, "announcement_id") + "/publish", null), 0);
        }
        String extra = pPost(plat, "/nfy/platform/api/v1/announcements",
                "{\"title\":\"配额21\",\"content\":\"c\",\"effective_at\":" + eff + ",\"expire_at\":" + exp
                        + ",\"biz_no\":\"pan-q-21\"}");
        expectCode(pPost(plat, "/nfy/platform/api/v1/announcements/" + extract(extra, "announcement_id") + "/publish", null), 10611);
        // 列表
        String list = pGet(plat, "/nfy/platform/api/v1/announcements?status=PUBLISHED");
        assertThatCode0(list);
        org.assertj.core.api.Assertions.assertThat(list).contains("配额1");
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
