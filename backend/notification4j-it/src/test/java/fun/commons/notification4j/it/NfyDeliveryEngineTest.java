package fun.commons.notification4j.it;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import fun.commons.notification4j.engine.ChannelSender;
import fun.commons.notification4j.entity.NfyaChannel;
import fun.commons.notification4j.entity.NfyaMessage;
import fun.commons.notification4j.entity.NfyaMessageRecipient;
import fun.commons.notification4j.entity.NfyaMessageType;
import fun.commons.notification4j.entity.NfyaTenant;
import fun.commons.notification4j.mapper.NfyaChannelMapper;
import fun.commons.notification4j.mapper.NfyaDeliveryMapper;
import fun.commons.notification4j.mapper.NfyaMessageMapper;
import fun.commons.notification4j.mapper.NfyaMessageRecipientMapper;
import fun.commons.notification4j.mapper.NfyaMessageTypeMapper;
import fun.commons.notification4j.mapper.NfyaTenantMapper;
import fun.commons.notification4j.entity.NfyaDelivery;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 第 6b 步：外发引擎（技术方案 §4.3）。
 * VECTOR: TAG=step6b-engine
 * 契约：领取 SKIP LOCKED → SENDING → SPI 投递 → 业务码成功置 SUCCESS + 渠道 fail_count 清零；
 * 失败退避 1/1/1s（测试配置）重试 ≤3 次后 DEAD；渠道连续失败 ≥5 熔断 DISABLED + 属主站内信；
 * Reaper 回收 SENDING 超时行。sender 以测试 Fake 替身（OCP：自定义 Bean 覆盖内建）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfyDeliveryEngineTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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
                "framework4j.sensitive.encryption-key=12345678901234567890123456789012",
                // 引擎：300ms 扫描、退避 1s、reaper 300ms/卡死阈值 2s（测试加速）
                "nfy.runtime.engine.enabled=true",
                "nfy.runtime.engine.scan-interval-ms=300",
                "nfy.runtime.engine.batch-size=50",
                "nfy.runtime.engine.reaper-interval-ms=300",
                "nfy.runtime.engine.sending-stale-ms=2000",
                "nfy.runtime.engine.backoff-seconds=1,1,1"
        })
@AutoConfigureMockMvc
@Tag("step6")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NfyDeliveryEngineTest {

    /** Fake sender 失败配额：>0 时前 N 次投递失败（原子），≤0 全部成功 */
    static final AtomicInteger FAILURES_REMAINING = new AtomicInteger(0);

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

        /** Fake 适配器（自定义 Bean 覆盖内建 IM/EMAIL：OCP 验证） */
        @Bean
        ChannelSender fakeSender() {
            return new ChannelSender() {
                @Override
                public String channelType() {
                    return "FAKE";
                }

                @Override
                public boolean supports(String channelType) {
                    return "DINGTALK".equals(channelType) || "EMAIL".equals(channelType);
                }

                @Override
                public SendResult send(NfyaDelivery d, NfyaChannel c) {
                    int remaining = FAILURES_REMAINING.getAndUpdate(v -> v > 0 ? v - 1 : v);
                    return remaining > 0 ? SendResult.fail("forced-fail") : SendResult.success("ok");
                }
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
    @Autowired NfyaMessageTypeMapper typeMapper;
    @Autowired NfyaChannelMapper channelMapper;
    @Autowired NfyaDeliveryMapper deliveryMapper;
    @Autowired NfyaMessageMapper messageMapper;
    @Autowired NfyaMessageRecipientMapper recipientMapper;

    private static final long TENANT_A = 88601L;

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
    }

    /** 造一条带 ENABLED 钉钉渠道的用户 → API 发送 → 返回 delivery 行 */
    private NfyaDelivery sendCreatingDelivery(String bizNo, String userid) throws Exception {
        NfyaMessageType type = new NfyaMessageType();
        type.setTenantId(TENANT_A);
        type.setTypeCode("ENG_" + bizNo);
        type.setName("t");
        type.setDefaultLevel("NORMAL");
        type.setDefaultChannels("[\"INAPP\",\"DINGTALK\"]");
        type.setMandatory(0);
        type.setBuiltIn(0);
        type.setStatus("ENABLED");
        type.setExt("{}");
        typeMapper.insert(type);

        NfyaChannel ch = new NfyaChannel();
        ch.setTenantId(TENANT_A);
        ch.setScope("USER");
        ch.setUserid(userid);
        ch.setChannelType("DINGTALK");
        ch.setName("渠道-" + userid);
        ch.setTarget("https://oapi.dingtalk.com/robot/send?access_token=" + bizNo);
        ch.setSecret("");
        ch.setKeyword("");
        ch.setStatus("ENABLED");
        ch.setFailCount(0);
        ch.setExt("{}");
        channelMapper.insert(ch);

        String jwt = token();
        String body = mvc.perform(post("/nfy/api/v1/runtime/messages")
                        .header("Authorization", "Bearer " + jwt).header("X-User-Id", userid)
                        .contentType("application/json")
                        .content("{\"type_code\":\"ENG_" + bizNo + "\",\"user_ids\":[\"" + userid + "\"],"
                                + "\"title\":\"引擎-" + bizNo + "\",\"content\":\"c\",\"biz_no\":\"" + bizNo + "\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"code\":0");
        return deliveryMapper.selectOne(new LambdaQueryWrapper<NfyaDelivery>()
                .eq(NfyaDelivery::getTenantId, TENANT_A)
                .eq(NfyaDelivery::getUserid, userid)
                .last("LIMIT 1"));
    }

    private String token() throws Exception {
        String body = mvc.perform(post("/nfy/api/v1/auth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=" + TENANT_A + "&client_secret=secret-a"))
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    private NfyaDelivery await(Long deliveryId, String status) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            NfyaDelivery d = deliveryMapper.selectById(deliveryId);
            if (d != null && status.equals(d.getStatus())) {
                return d;
            }
            Thread.sleep(200);
        }
        NfyaDelivery d = deliveryMapper.selectById(deliveryId);
        throw new AssertionError("等待 " + status + " 超时，当前: "
                + (d == null ? "null" : d.getStatus() + "/" + d.getRetryCount() + "/" + d.getErrorMessage()));
    }

    @Test
    @Order(1)
    void engine_delivers_pending_and_resets_fail_count() throws Exception {
        FAILURES_REMAINING.set(0);
        NfyaDelivery d = sendCreatingDelivery("eng_1", "u_ok");
        NfyaDelivery done = await(d.getId(), "SUCCESS");
        assertThat(done.getSentAt()).isNotNull();
        NfyaChannel ch = channelMapper.selectById(d.getChannelId());
        assertThat(ch.getFailCount()).isZero();
        // ND-L5-01：投递成功即渠道可用性证据 → last_verify_at 回填（渠道离线插入时为 null）
        assertThat(ch.getLastVerifyAt()).as("投递成功后 last_verify_at 非空").isNotNull();
    }

    @Test
    @Order(2)
    void engine_retries_then_dead_and_breaks_channel_with_owner_inbox() throws Exception {
        FAILURES_REMAINING.set(99); // 持续失败
        try {
            NfyaDelivery d1 = sendCreatingDelivery("eng_fail_1", "u_fail");
            NfyaDelivery dead = await(d1.getId(), "DEAD");
            assertThat(dead.getRetryCount()).isEqualTo(4); // 初始+3 次重试
            assertThat(dead.getErrorMessage()).contains("forced-fail");
            NfyaChannel ch = channelMapper.selectById(d1.getChannelId());
            assertThat(ch.getFailCount()).isEqualTo(4); // 尚未熔断（阈值 5）

            // DLV-002 人工重投 DEAD 行 → 引擎再失败一次 → fail_count=5 → 熔断 + 属主站内信
            String jwt = token();
            String retried = mvc.perform(post("/nfy/api/v1/admin/deliveries/" + d1.getId() + "/retry")
                            .header("Authorization", "Bearer " + jwt))
                    .andReturn().getResponse().getContentAsString();
            assertThat(retried).contains("\"code\":0");
            await(d1.getId(), "DEAD");
            NfyaChannel disabled = channelMapper.selectById(d1.getChannelId());
            org.junit.jupiter.api.Assertions.assertEquals("DISABLED", disabled.getStatus());
            // 属主站内信
            NfyaMessage inbox = messageMapper.selectOne(new LambdaQueryWrapper<NfyaMessage>()
                    .eq(NfyaMessage::getTenantId, TENANT_A)
                    .eq(NfyaMessage::getTypeCode, "CHANNEL_ALERT")
                    .last("LIMIT 1"));
            assertThat(inbox).as("熔断属主站内信").isNotNull();
            NfyaMessageRecipient r = recipientMapper.selectOne(new LambdaQueryWrapper<NfyaMessageRecipient>()
                    .eq(NfyaMessageRecipient::getMessageId, inbox.getId()));
            assertThat(r).isNotNull();
            assertThat(r.getUserid()).isEqualTo("u_fail");
        } finally {
            FAILURES_REMAINING.set(0);
        }
    }

    @Test
    @Order(3)
    void reaper_recovers_stale_sending_row() throws Exception {
        FAILURES_REMAINING.set(0);
        // 手工插入 SENDING 伪卡死行（updated_at 直插旧值；INSERT 不触发 UPDATE 触发器）
        NfyaDelivery stale = new NfyaDelivery();
        stale.setTenantId(TENANT_A);
        stale.setSourceType("MESSAGE");
        stale.setSourceId(1L);
        stale.setUserid("u_reap");
        stale.setChannelId(1L); // 假渠道：reaper 只看 SENDING 状态，与渠道无关
        stale.setChannelType("DINGTALK");
        stale.setTarget("t");
        stale.setTitle("t");
        stale.setStatus("SENDING");
        stale.setRetryCount(0);
        stale.setNextRetryAt(OffsetDateTime.now().minusHours(1));
        stale.setErrorMessage("");
        stale.setTraceId("");
        stale.setUpdatedAt(OffsetDateTime.now().minusMinutes(11));
        stale.setExt("{}");
        deliveryMapper.insert(stale);
        // reaper 周期 300ms → 回收为 PENDING（随后即便被领取投递，也不会停留在 SENDING）
        for (int i = 0; i < 60; i++) {
            NfyaDelivery d = deliveryMapper.selectById(stale.getId());
            if (d != null && !"SENDING".equals(d.getStatus())) {
                return; // 离开 SENDING 即视为回收成功（PENDING 或后续被投成 SUCCESS/FAILED）
            }
            Thread.sleep(200);
        }
        throw new AssertionError("reaper 未回收 SENDING 行");
    }
}
