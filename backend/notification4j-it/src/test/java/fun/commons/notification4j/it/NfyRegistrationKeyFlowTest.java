package fun.commons.notification4j.it;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import fun.commons.notification4j.entity.NfypRegistrationKey;
import fun.commons.notification4j.mapper.NfypRegistrationKeyMapper;
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

/**
 * 第 13 步：注册码闭环（API-PRK-001 平台签发 + API-OPEN-001 开放域自助注册，§4.1/§4.4/§5.9.2）。
 * VECTOR: TAG=step13-regkey
 * 契约：
 * - PRK-001（平台域）签发 {max_uses, expire_hours, preset} → {registration_key} 一次性显示；
 * - OPEN-001（开放域无鉴权）{registration_key, name} → {open_id, tenant_secret} 明文一次；
 *   原子扣减（DB 单语句 CAS：used_count<max_uses AND ACTIVE AND 未过期）→ 违规 10608 同码防探测；
 *   消费后回填 consumed_tenant_id；preset 预绑到新租户。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfyRegistrationKeyFlowTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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
                "framework4j.access-token.exclude-path-patterns=/nfy/api/v1/auth/token,/nfy/open/**",
                "framework4j.access-token.policies.TENANT.key=tenant_id",
                "framework4j.access-token.policies.TENANT.expire-time=28800",
                "framework4j.access-token.policies.PLATFORM.key=tenant_id",
                "framework4j.access-token.policies.PLATFORM.expire-time=28800",
                "framework4j.sensitive.enabled=true",
                "framework4j.sensitive.encryption-key=12345678901234567890123456789012"
        })
@AutoConfigureMockMvc
@Tag("step13")
class NfyRegistrationKeyFlowTest {

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
    @Autowired NfypRegistrationKeyMapper keyMapper;

    private static final long PLATFORM_ACTOR = 0L;

    @BeforeAll
    void init() {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
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

    private String issue(String jwt, String body) throws Exception {
        return mvc.perform(post("/nfy/platform/api/v1/registration-keys")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String openRegister(String key, String name) throws Exception {
        return mvc.perform(post("/nfy/open/api/v1/tenants/register")
                        .contentType("application/json")
                        .content("{\"registration_key\":\"" + key + "\",\"name\":\"" + name + "\"}"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String extract(String body, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\":\"([^\"]+)\"").matcher(body);
        org.assertj.core.api.Assertions.assertThat(m.find()).as("字段 %s 存在: %s", field, body).isTrue();
        return m.group(1);
    }

    @Test
    void issue_register_end_to_end_and_exhausted_10608() throws Exception {
        String plat = platformToken();
        String issued = issue(plat, "{\"max_uses\":1,\"expire_hours\":24,"
                + "\"preset\":{\"privileges\":{\"signature\":false}}}");
        assertThatCode0(issued);
        String key = extract(issued, "registration_key");

        // 开放域无鉴权自助注册 → 明文一次
        String registered = openRegister(key, "自助租户");
        assertThatCode0(registered);
        String openId = extract(registered, "open_id");
        String secret = extract(registered, "tenant_secret");

        // 新租户端到端：换 token
        String auth = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=" + openId + "&client_secret=" + secret))
                .andReturn().getResponse().getContentAsString();
        assertThatCode0(auth);

        // 用尽 → 再注册 10608（同码防探测：未知码同文案）
        String again = openRegister(key, "自助租户2");
        expectCode(again, 10608);
        String unknown = openRegister("nonexistent-key-000", "x");
        expectCode(unknown, 10608);

        // consumed_tenant_id 回填
        NfypRegistrationKey row = keyMapper.selectOne(new LambdaQueryWrapper<NfypRegistrationKey>()
                .eq(NfypRegistrationKey::getCode, key));
        org.assertj.core.api.Assertions.assertThat(row.getUsedCount()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(row.getConsumedTenantId()).isNotNull().isNotZero();

        // 新租户端到端发消息（认证+数据面）
        String jwt = auth.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        String typeBody = mvc.perform(post("/nfy/api/v1/admin/types")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json")
                        .content("{\"type_code\":\"OPEN_T\",\"name\":\"t\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThatCode0(typeBody);
    }

    @Test
    void multi_use_key_and_expired_and_list_masked() throws Exception {
        String plat = platformToken();
        String issued = issue(plat, "{\"max_uses\":2,\"expire_hours\":24}");
        String key = extract(issued, "registration_key");

        assertThatCode0(openRegister(key, "多次租户1"));
        assertThatCode0(openRegister(key, "多次租户2"));
        expectCode(openRegister(key, "多次租户3"), 10608);

        // 过期码 → 10608
        NfypRegistrationKey expired = new NfypRegistrationKey();
        expired.setCode("expired-key-test-0001");
        expired.setMaxUses(1);
        expired.setUsedCount(0);
        expired.setStatus("ACTIVE");
        expired.setExpireAt(OffsetDateTime.now().minusHours(1));
        expired.setConsumedTenantId(0L);
        expired.setIssueBy("");
        expired.setExt("{}");
        keyMapper.insert(expired);
        expectCode(openRegister("expired-key-test-0001", "过期租户"), 10608);

        // 列表：code 脱敏（完整码不出现）
        String list = mvc.perform(get("/nfy/platform/api/v1/registration-keys")
                        .header("Authorization", "Bearer " + plat))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThatCode0(list);
        org.assertj.core.api.Assertions.assertThat(list).contains("****").doesNotContain(key);
    }

    private void expectCode(String body, int code) {
        org.assertj.core.api.Assertions.assertThat(body).contains("\"code\":" + code);
    }

    private void assertThatCode0(String body) {
        org.assertj.core.api.Assertions.assertThat(body).contains("\"code\":0").doesNotContain("\"fail\":true");
    }
}
