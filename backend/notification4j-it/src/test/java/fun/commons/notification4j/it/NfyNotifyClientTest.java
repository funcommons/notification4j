package fun.commons.notification4j.it;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import fun.commons.notification4j.client.AnnounceRequest;
import fun.commons.notification4j.client.NotifyClient;
import fun.commons.notification4j.client.SendMessageRequest;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.mybatis.spring.annotation.MapperScan;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 8b 步：NotifyClient 双模式门面（双模式技术方案 §5.1/§5.3）——local 分支集成。
 * VECTOR: TAG=step8-client
 * 契约：业务方只依赖 NotifyClient（不感知 local/remote）；
 * local 模式直连 service 全链路（send=站内+投递计划；announce=创建即发布；
 * unreadCount=站内+公告未确认合成；listMessages=Cursor）；
 * nfy.client.enabled 显式开启（默认关：不给嵌入方强加门面/失败语义）。
 * remote 分支（HTTP 面）在 NfyRemoteClientUnitTest 以 fake transport 单测覆盖。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfyNotifyClientTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.application.name=notification4j-it",
                "spring.flyway.enabled=false",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure",
                "framework4j.tenant.enabled=false",
                "framework4j.redis.enabled=false",
                "framework4j.datasource.enabled=false",
                "framework4j.cache.enabled=false",
                "framework4j.audit.enabled=false",
                "framework4j.idempotency.enabled=false",
                "framework4j.rate-limit.enabled=false",
                "framework4j.signature.enabled=false",
                "framework4j.sensitive.enabled=true",
                "framework4j.sensitive.encryption-key=12345678901234567890123456789012",
                "framework4j.access-token.enabled=false",
                "framework4j.sql-tracing.enabled=false",
                "framework4j.tracelog.enabled=false",
                "framework4j.transport.enabled=false",
                "framework4j.id.enabled=false",
                "nfy.data.enabled=true",
                "nfy.runtime.client-enabled=true"
        })
@Tag("step8")
class NfyNotifyClientTest {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");

    static {
        PG.start();
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
        com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator identifierGenerator() {
            return new com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator();
        }
    }

    @Autowired NotifyClient notifyClient;
    @Autowired NfyaTenantMapper tenantMapper;
    @Autowired NfyaMessageTypeMapper typeMapper;

    private static final long TENANT = 88801L;

    @BeforeAll
    void init() {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        NfyaTenant t = new NfyaTenant();
        t.setId(TENANT);
        t.setName("A");
        t.setStatus("ACTIVE");
        t.setTenantSecret("s");
        tenantMapper.insert(t);
        NfyaMessageType type = new NfyaMessageType();
        type.setTenantId(TENANT);
        type.setTypeCode("CLI_T");
        type.setName("t");
        type.setDefaultLevel("NORMAL");
        type.setDefaultChannels("[\"INAPP\"]");
        type.setMandatory(0);
        type.setBuiltIn(0);
        type.setStatus("ENABLED");
        type.setExt("{}");
        typeMapper.insert(type);
    }

    @Test
    void local_send_then_unread_then_list() {
        assertThat(notifyClient).isInstanceOf(fun.commons.notification4j.client.LocalNotifyClient.class);

        NotifyClient.SendMessageResult r = notifyClient.send(TENANT, SendMessageRequest.of(
                "CLI_T", List.of("u_1", "u_1", "u_2"), "标题", "内容", null, null, "cli-1"));
        assertThat(r.messageId()).isNotBlank();
        assertThat(r.bizNo()).isEqualTo("cli-1");
        assertThat(r.receiverCount()).isEqualTo(2); // 去重

        NotifyClient.UnreadSummary u1 = notifyClient.unreadCount(TENANT, "u_1");
        assertThat(u1.unreadCount()).isEqualTo(1);
        assertThat(u1.unconfirmedCount()).isZero();

        NotifyClient.MessagePage page = notifyClient.listMessages(TENANT, "u_1", null, 10);
        assertThat(page.list()).hasSize(1);
        assertThat(page.list().get(0).title()).isEqualTo("标题");
    }

    @Test
    void local_announce_publishes_immediately() {
        NotifyClient.AnnounceResult a = notifyClient.announce(TENANT, AnnounceRequest.of(
                "门面公告", "内容", null, 1, null, "cli-ann-1"));
        assertThat(a.announcementId()).isNotBlank();

        // 同 biz_no 重复 announce → uk_nfya_ann_tenant_biz_no 幂等闸 → 10401
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> notifyClient.announce(TENANT,
                        AnnounceRequest.of("门面公告2", "内容", null, 0, null, "cli-ann-1")))
                .isInstanceOf(fun.commons.notification4j.client.NfyClientException.class)
                .hasMessageContaining("10401");
    }
}
