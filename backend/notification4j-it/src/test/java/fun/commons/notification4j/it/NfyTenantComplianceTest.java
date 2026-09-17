package fun.commons.notification4j.it;

import com.zaxxer.hikari.HikariDataSource;
import fun.commons.framework4j.tenant.tck.TenantComplianceSuite;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

/**
 * framework4j-tenant TCK 合规闸（列集契约/唯一索引/业务表 tenant_id 打头）。
 * DDL 级三测在本步生效；认证级四测依赖第 2 步端点（context 默认实现为通过语义，届时覆盖断言）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfyTenantComplianceTest.TestBoot.class, properties = {
        "framework4j.tenant.enabled=false", "framework4j.redis.enabled=false",
        "framework4j.datasource.enabled=false", "framework4j.cache.enabled=false",
        "framework4j.audit.enabled=false", "framework4j.idempotency.enabled=false",
        "framework4j.rate-limit.enabled=false", "framework4j.signature.enabled=false",
        "framework4j.sensitive.enabled=false", "framework4j.access-token.enabled=false",
        "framework4j.sql-tracing.enabled=false", "framework4j.tracelog.enabled=false",
        "framework4j.transport.enabled=false", "framework4j.id.enabled=false", "nfy.data.enabled=false",
        "spring.flyway.enabled=false",
        "spring.autoconfigure.exclude=com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"})
@Tag("step1")
class NfyTenantComplianceTest extends TenantComplianceSuite {

    // 静态块手动启动: @SpringBootTest 上下文先于 Testcontainers 扩展创建, @Container 注解启动太晚
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");

    static {
        PG.start();
    }

    /** 套件 @SpringBootTest 锚点 + 契约字段(dataSource/jdbcTemplate @Autowired)来源 */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestBoot {

        @Bean
        javax.sql.DataSource tckDataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(PG.getJdbcUrl());
            ds.setUsername(PG.getUsername());
            ds.setPassword(PG.getPassword());
            return ds;
        }

        @Bean
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate(javax.sql.DataSource ds) {
            return new org.springframework.jdbc.core.JdbcTemplate(ds);
        }
    }

    @BeforeAll
    void init() {
        // 套件字段由 Spring 注入(TestBoot.tckDataSource); 此处仅保证 Flyway 已迁移
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Override
    public TenantComplianceContext complianceContext() {
        return new TenantComplianceContext() {
            @Override
            public String tenantTable() {
                return "nfya_tenant";
            }

            @Override
            public List<String> businessTables() {
                // 租户主表外全部业务表(索引/唯一键 tenant_id 打头); registration_key/audit 为平台层豁免
                return List.of("nfya_message_type", "nfya_message", "nfya_message_recipient",
                        "nfya_announcement", "nfya_announcement_read", "nfya_channel",
                        "nfya_subscription", "nfya_template", "nfya_delivery");
            }
        };
    }
}
