package fun.commons.notification4j.it;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 静态页托管套件（编码第 29 步「免双部署」）：前端 SPA 入 starter jar + history 路由 fallback + API 面优先。
 * VECTOR: 静态资源托管
 *
 * <p>骨架复制 {@link NfySmokeTest} 静态部分（PG/Redis 容器 + TestApp 手工 DataSource/Redis 装配），
 * 但 webEnvironment 用 RANDOM_PORT（真容器）而非 MOCK——理由：SPA fallback 是
 * {@code forward:/assets/index.html}（服务端 RequestDispatcher 转发），MockMvc 的
 * MockRequestDispatcher 只记录 forwardedUrl <b>不真实执行转发</b>，断言不到转发终点的
 * Content-Type/响应体；「深链刷新拿到 index.html」的浏览器语义必须经真实分发验证。</p>
 *
 * <p>it 模块依赖 starter（非 app），静态产物与 fallback 控制器同在 starter classpath（裁决见
 * {@code NfyStaticPageConfig} 类注），故本套件即独立部署（app）与嵌入（starter 开关）共路径的真实验证。</p>
 *
 * <p>断言面（{@code nfy.static-page.enabled=true}，app 出厂口径）：</p>
 * <ol>
 *   <li>{@code GET /} → 200 text/html 且含 SPA 挂载点 {@code <div id="app">}</li>
 *   <li>{@code GET /nfy/tenant/**} 深链刷新 → 200 index.html（history fallback 生效）</li>
 *   <li>API 面优先：已映射 {@code /nfy/api/v1/ops/health} 照常 code=0 信封；未映射 API 路径
 *       （api/open/platform 三前缀）不被静态页吞成 200 HTML，照常 404</li>
 *   <li>{@code /assets/**} 资源可达（真实 hash 产物 + SPA 入口直达）</li>
 *   <li>public 根文件（favicon.svg / logo.svg）可达</li>
 * </ol>
 *
 * <p>开关关闭路径（嵌入方默认）：{@code @ConditionalOnProperty(havingValue="true")} 声明式缺省关闭，
 * 无可执行行为可测（装配整体缺席），不另起第二个上下文（省一次容器冷启动）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfyStaticPageTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.application.name=notification4j-it",
                "nfy.runtime.enable-api=true",
                "nfy.static-page.enabled=true",
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
class NfyStaticPageTest {

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

    @Autowired TestRestTemplate rest;

    // ---------- ① 根路径即 SPA 入口 ----------

    @Test
    void t1_root_serves_spa_index() {
        ResponseEntity<String> r = rest.getForEntity("/", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertContentType(r, MediaType.TEXT_HTML);
        assertThat(r.getBody()).contains("<div id=\"app\">");
    }

    // ---------- ② history 路由深链 fallback（app 壳 + bell 单页壳，对齐前端路由表） ----------

    @Test
    void t2_history_route_fallback() {
        for (String route : List.of(
                "/nfy/tenant/app/messages",
                "/nfy/tenant/app/announcements",
                "/nfy/tenant/app/deliveries",
                "/nfy/tenant/page/bell")) {
            ResponseEntity<String> r = rest.getForEntity(route, String.class);
            assertThat(r.getStatusCode()).as("深链 %s 应回落 index.html", route).isEqualTo(HttpStatus.OK);
            assertContentType(r, MediaType.TEXT_HTML);
            assertThat(r.getBody()).contains("<div id=\"app\">");
        }
    }

    // ---------- ③ API 面优先：已映射照常信封，未映射不被静态页吞 ----------

    @Test
    void t3_api_face_not_swallowed() {
        // 已映射 API：code=0 信封（db/redis 探针 UP），与静态托管共存
        ResponseEntity<String> health = rest.getForEntity("/nfy/api/v1/ops/health", String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) JsonPath.read(health.getBody(), "$.code")).isZero();
        assertThat((String) JsonPath.read(health.getBody(), "$.data.checks.db.status")).isEqualTo("UP");
        assertThat((String) JsonPath.read(health.getBody(), "$.data.checks.redis.status")).isEqualTo("UP");

        // 未映射 API 路径：三前缀均 404（fallback 映射 /nfy/tenant/** 与 API 前缀零交集，
        // 且不注册 /** 宽泛 handler——未命中照常走 NoResource）
        for (String unknown : List.of(
                "/nfy/api/v1/not-exist",
                "/nfy/open/not-exist",
                "/nfy/platform/api/v1/not-exist")) {
            assertThat(rest.getForEntity(unknown, String.class).getStatusCode())
                    .as("未映射 API %s 不被静态页吞", unknown).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ---------- ④ /assets/** 真实产物可达（hash chunk + SPA 入口直达） ----------

    @Test
    void t4_assets_reachable() throws java.io.IOException {
        // 从 classpath 枚举一个真实 hash 产物名（产物带内容 hash，测试不硬编码文件名）
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] chunks = resolver.getResources("classpath*:nfy-console/assets/*.js");
        assertThat(chunks).as("starter jar 内应有前端 hash 产物").isNotEmpty();
        String chunkName = chunks[0].getFilename();
        assertThat(chunkName).isNotBlank();

        ResponseEntity<String> chunk = rest.getForEntity("/assets/" + chunkName, String.class);
        assertThat(chunk.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertContentType(chunk, MediaType.valueOf("text/javascript"));

        // SPA 入口（fallback forward 目标）以 /assets/index.html 直达同样可达
        ResponseEntity<String> entry = rest.getForEntity("/assets/index.html", String.class);
        assertThat(entry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entry.getBody()).contains("<div id=\"app\">");
    }

    // ---------- ⑤ public 根文件（index.html 图标 + bundle 引用的 logo） ----------

    @Test
    void t5_public_root_files_reachable() {
        for (String file : List.of("/favicon.svg", "/logo.svg")) {
            ResponseEntity<String> r = rest.getForEntity(file, String.class);
            assertThat(r.getStatusCode()).as("public 根文件 %s", file).isEqualTo(HttpStatus.OK);
            assertContentType(r, MediaType.valueOf("image/svg+xml"));
        }
    }

    /** Content-Type 兼容断言（AssertJ 对 MediaType 无 isCompatibleWith，借 MediaType#isCompatibleWith 语义） */
    private void assertContentType(ResponseEntity<String> r, MediaType expected) {
        MediaType actual = r.getHeaders().getContentType();
        assertThat(actual).as("Content-Type 应存在").isNotNull();
        assertThat(actual.isCompatibleWith(expected)).as("Content-Type %s 兼容 %s", actual, expected).isTrue();
    }
}
