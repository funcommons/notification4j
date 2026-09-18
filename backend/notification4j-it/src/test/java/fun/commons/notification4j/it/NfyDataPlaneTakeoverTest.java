package fun.commons.notification4j.it;

import com.alibaba.druid.pool.DruidDataSource;
import fun.commons.notification4j.entity.NfyaTenant;
import fun.commons.notification4j.mapper.NfyaTenantMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * V1.3 数据面接管回归（GitHub issue #3；L6 平台治理线）。
 * <p>
 * 复刻 MManiX2 薄壳形态：<b>只配</b> {@code framework4j.datasource.datasources.default.*}，
 * {@code spring.autoconfigure.exclude} 留空（本套件是唯一不手工排除原生装配的 IT——
 * 原生 DataSource/Druid/MybatisPlus/TxManager/Redisson 的退场完全依赖 starter 的
 * {@code NfyDataPlaneTakeoverFilter}，filter 由 framework4j.datasource.enabled /
 * framework4j.redis.enabled 闸门激活）。
 * <p>
 * 修复前：druid 原生 DruidDataSourceAutoConfigure（字典序先于 framework4j、matchIfMissing=true）
 * 注册 DruidDataSourceWrapper → afterPropertiesSet 向 spring.datasource.* 索要 url → 启动即炸
 * 「Failed to configure a DataSource」；补 spring.datasource.* 则同库双池。
 * <p>
 * 契约断言：
 * - 上下文可启动（零 spring.datasource.* / 零手工 exclude）；
 * - 单池：DataSource bean 仅 defaultDataSource（Druid），无 Hikari；
 * - 单工厂：SqlSessionFactory 仅 defaultSqlSessionFactory，MP 原生 sqlSessionFactory 不在场；
 * - 业务链路通：token 签发 → OEM-001 hosts 下发（mapper 全部落 framework4j 池）；
 * - 附带（issue #2）：TestApp 不注册 IdentifierGenerator，framework4j-id 兜底 +
 *   MP builder 自兜底下插入自动生成雪花 id。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfyDataPlaneTakeoverTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.application.name=notification4j-it",
                "nfy.runtime.enable-api=true",
                "spring.flyway.enabled=false",
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
                "framework4j.sensitive.encryption-key=12345678901234567890123456789012",
                "framework4j.datasource.enabled=true",
                "framework4j.datasource.datasources.default.druid.filters=stat,slf4j"
        })
@AutoConfigureMockMvc
class NfyDataPlaneTakeoverTest {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        PG.start();
        REDIS.start();
    }

    /** 数据源指向走动态属性（容器端口启动后可知；filter 闸门 framework4j.datasource.enabled=true） */
    @DynamicPropertySource
    static void framework4jDatasource(DynamicPropertyRegistry r) {
        r.add("framework4j.datasource.datasources.default.url", () -> PG.getJdbcUrl() + "?stringtype=unspecified");
        r.add("framework4j.datasource.datasources.default.username", PG::getUsername);
        r.add("framework4j.datasource.datasources.default.password", PG::getPassword);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
    @MapperScan("fun.commons.notification4j.mapper")
    static class TestApp {
        // 刻意不提供 DataSource / IdentifierGenerator bean —— 数据面完全由 framework4j 接管
        // （druid 池 + SqlSessionFactory 由 registrar 注册；雪花 id 由 framework4j-id 兜底）

        @Bean
        org.springframework.data.redis.connection.RedisConnectionFactory redisConnectionFactory() {
            org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory f =
                    new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
            f.afterPropertiesSet();
            lettuceFactory = f;
            return f;
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
    @Autowired ApplicationContext ctx;
    @Autowired NfyaTenantMapper tenantMapper;

    private static long TENANT_ID = 89321L;

    @BeforeAll
    void init() throws Exception {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        // 不显式给 id —— 雪花 id 必须由兜底链生成（framework4j-id / MP builder 自兜底）
        NfyaTenant t = new NfyaTenant();
        t.setName("TAKEOVER");
        t.setStatus("ACTIVE");
        t.setTenantSecret("placeholder");   // NOT NULL 列占位；id 生成后按真实 id 回填
        tenantMapper.insert(t);
        org.junit.jupiter.api.Assertions.assertNotNull(t.getId(), "雪花 id 必须由兜底链生成");
        TENANT_ID = t.getId();
        t.setTenantSecret("secret-" + TENANT_ID);  // updateById 走 typeHandler 加密通道
        tenantMapper.updateById(t);
    }

    private String token() throws Exception {
        String body = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=" + TENANT_ID + "&client_secret=secret-" + TENANT_ID))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("access_token");
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void TAKEOVER_原生装配退场_单池单工厂() {
        Map<String, DataSource> dataSources = ctx.getBeansOfType(DataSource.class);
        assertThat(dataSources).hasSize(1);
        assertThat(dataSources.keySet()).containsExactly("defaultDataSource");
        assertThat(dataSources.values().iterator().next()).isInstanceOf(DruidDataSource.class);
        assertThat(ctx.getBeansOfType(org.springframework.jdbc.datasource.embedded.EmbeddedDatabase.class)).isEmpty();

        Map<String, SqlSessionFactory> factories = ctx.getBeansOfType(SqlSessionFactory.class);
        assertThat(factories).hasSize(1);
        assertThat(factories.keySet()).containsExactly("defaultSqlSessionFactory");
        // MP 原生装配的默认 bean 名不应在场（MybatisPlusAutoConfiguration 被过滤）
        assertThat(ctx.containsBean("sqlSessionFactory")).isFalse();
        // Hikari 不应引入第二池
        assertThat(ctx.getBeansOfType(com.zaxxer.hikari.HikariDataSource.class)).isEmpty();
    }

    @Test
    void TAKEOVER_业务链路在_framework4j_池上全通() throws Exception {
        String jwt = token();
        // OEM-001（V1.3）：mapper 走 framework4j druid 池的端到端读
        tenantMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<NfyaTenant>()
                .eq("id", TENANT_ID)
                .setSql("oem = {0}::jsonb", "{\"hosts\":[\"https://app.example.com\"]}"));
        String body = mvc.perform(get("/nfy/api/v1/runtime/oem/hosts")
                        .header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("\"code\":0").contains("https://app.example.com");
    }
}
