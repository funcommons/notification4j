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

/**
 * 第 16 步：模板域（API-TPL-001/002/003，§5.9.2；TENANT admin 面；V1.1 契约提前落地）。
 * VECTOR: TAG=step16-tpl
 * 契约：
 * - TPL-001 创建 {template_code, name, type_code, title_tpl, content_tpl, channel_content{}} →
 *   列表；template_code 租户内唯一（uk_nfya_template_code）10401；
 * - TPL-002 详情/修改/删除（逻辑删，删除后同 code 可重建）；
 * - TPL-003 preview 传 params → {{param}} 占位符渲染；参数缺失 → 10603「模板参数缺失:{name}」；
 *   channel_content 按渠道覆盖 title/content（{DINGTALK:{title_tpl,content_tpl}}）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfyTemplateTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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
@Tag("step16")
class NfyTemplateTest {

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

    private static final long TENANT_A = 89501L;
    private static final long TENANT_B = 89502L;

    @BeforeAll
    void init() throws Exception {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        for (long[] e : new long[][]{{TENANT_A}, {TENANT_B}}) {
            NfyaTenant t = new NfyaTenant();
            t.setId(e[0]);
            t.setName("T" + e[0]);
            t.setStatus("ACTIVE");
            t.setTenantSecret("secret-" + e[0]);
            tenantMapper.insert(t);
        }
    }

    private String token(long tenantId) throws Exception {
        String body = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=" + tenantId + "&client_secret=secret-" + tenantId))
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    private String create(String jwt, String body) throws Exception {
        return mvc.perform(post("/nfy/api/v1/admin/templates")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String preview(String jwt, String body) throws Exception {
        return mvc.perform(post("/nfy/api/v1/admin/templates/preview")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void create_detail_patch_delete_and_code_unique() throws Exception {
        String jwt = token(TENANT_A);
        String created = create(jwt, "{\"template_code\":\"ORDER_TPL\",\"name\":\"订单模板\","
                + "\"type_code\":\"ORDER\",\"title_tpl\":\"订单 {{orderNo}} 已支付\","
                + "\"content_tpl\":\"订单 **{{orderNo}}** 金额 {{amount}} 元\","
                + "\"channel_content\":{\"DINGTALK\":{\"content_tpl\":\"钉钉:{{orderNo}}\"}}}");
        assertThatCode0(created);
        String id = extract(created, "template_id");

        // 同 code 重复 → 10401；跨租户同 code 不冲突（租户内唯一）
        expectCode(create(jwt, "{\"template_code\":\"ORDER_TPL\",\"name\":\"重复\","
                + "\"type_code\":\"ORDER\",\"title_tpl\":\"t\",\"content_tpl\":\"c\"}"), 10401);
        String jwtB = token(TENANT_B);
        assertThatCode0(create(jwtB, "{\"template_code\":\"ORDER_TPL\",\"name\":\"B用\","
                + "\"type_code\":\"ORDER\",\"title_tpl\":\"t\",\"content_tpl\":\"c\"}"));

        // 列表 + 详情
        String list = mvc.perform(get("/nfy/api/v1/admin/templates")
                        .header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(list).contains("订单模板");
        String detail = mvc.perform(get("/nfy/api/v1/admin/templates/" + id)
                        .header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(detail).contains("{{orderNo}}");

        // PATCH 改名 → 详情回显；DELETE → 逻辑删
        assertThatCode0(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/nfy/api/v1/admin/templates/" + id)
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json").content("{\"name\":\"改名模板\"}"))
                .andReturn().getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(mvc.perform(get("/nfy/api/v1/admin/templates/" + id)
                        .header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("改名模板");
        assertThatCode0(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/nfy/api/v1/admin/templates/" + id)
                        .header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString());
        // 删除后同 code 可重建
        assertThatCode0(create(jwt, "{\"template_code\":\"ORDER_TPL\",\"name\":\"重建\","
                + "\"type_code\":\"ORDER\",\"title_tpl\":\"t\",\"content_tpl\":\"c\"}"));
    }

    @Test
    void preview_renders_params_channel_content_and_10603_missing() throws Exception {
        String jwt = token(TENANT_A);
        create(jwt, "{\"template_code\":\"PRV_TPL\",\"name\":\"渲染模板\","
                + "\"type_code\":\"ORDER\",\"title_tpl\":\"订单 {{orderNo}} 已支付\","
                + "\"content_tpl\":\"金额 {{amount}} 元\","
                + "\"channel_content\":{\"DINGTALK\":{\"content_tpl\":\"钉钉渠道 {{orderNo}}\"}}}");

        // 全参数渲染
        String ok = preview(jwt, "{\"template_code\":\"PRV_TPL\",\"type_code\":\"ORDER\","
                + "\"params\":{\"orderNo\":\"A123\",\"amount\":\"99\"}}");
        assertThatCode0(ok);
        org.assertj.core.api.Assertions.assertThat(ok)
                .contains("订单 A123 已支付").contains("金额 99 元")
                .contains("钉钉渠道 A123");

        // 缺参数 → 10603 且 message 含参数名
        String missing = preview(jwt, "{\"template_code\":\"PRV_TPL\",\"type_code\":\"ORDER\","
                + "\"params\":{\"orderNo\":\"A123\"}}");
        expectCode(missing, 10603);
        org.assertj.core.api.Assertions.assertThat(missing).contains("amount");

        // 未知模板 → 10400
        expectCode(preview(jwt, "{\"template_code\":\"GHOST\",\"type_code\":\"ORDER\",\"params\":{}}"), 10400);
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
