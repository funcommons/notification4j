package fun.commons.notification4j.it;

import fun.commons.framework4j.signature.util.SignatureUtil;
import fun.commons.framework4j.transport.HttpTransport;
import fun.commons.notification4j.client.NotifyClient;
import fun.commons.notification4j.remote.ClientAutoConfiguration;
import fun.commons.notification4j.remote.NfyHmacSigner;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编码第 30 步：notification4j-client-starter（部署形态三·跨进程 remote 轻量接入）。
 * VECTOR: TAG=step30
 * 本套件只放「跨模块才能断言」的用例（其余归 client-starter 模块自身单测 NfyClientStarterUnitTest）：
 * ① HMAC 签名器与 framework4j-signature SignatureUtil 逐字节等价（本地化签名的防漂移闸，需 framework4j-all）；
 * ② 双 starter 共存的确定性让位（互斥契约，见 README 部署节；同 classpath 属测试编排，
 *    client-enabled 闸保证既有套件零影响）；
 * ③ 真实 imports 注册 + @AutoConfigureAfter(name) 次序的端到端（@EnableAutoConfiguration 全管线）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = NfyClientStarterTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.application.name=notification4j-it",
                "spring.flyway.enabled=false",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
                "framework4j.tenant.enabled=false",
                "framework4j.redis.enabled=false",
                "framework4j.datasource.enabled=false",
                "framework4j.cache.enabled=false",
                "framework4j.audit.enabled=false",
                "framework4j.idempotency.enabled=false",
                "framework4j.rate-limit.enabled=false",
                "framework4j.signature.enabled=false",
                "framework4j.sensitive.enabled=false",
                "framework4j.access-token.enabled=false",
                "framework4j.sql-tracing.enabled=false",
                "framework4j.tracelog.enabled=false",
                "framework4j.transport.enabled=false",
                "framework4j.id.enabled=false",
                "nfy.data.enabled=false",
                "nfy.runtime.client-enabled=true",
                "nfy.runtime.mode=remote",
                "nfy.runtime.remote-url=http://nfy-svc:9200",
                "nfy.runtime.remote-tenant-id=t_openid",
                "nfy.runtime.remote-tenant-secret=s3cret"
        })
@Tag("step30")
class NfyClientStarterTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
    }

    @Autowired
    ApplicationContext ctx;

    /**
     * ③ 端到端：两个 starter 的 AutoConfiguration.imports 均被真实导入，全量 starter 先注册、
     * client-starter 经 @ConditionalOnMissingBean 确定性让位——单 NotifyClient、transport 归全量 starter。
     */
    @Test
    void coexisting_starters_yield_deterministically_end_to_end() {
        assertThat(ctx.getBeanNamesForType(NotifyClient.class)).hasSize(1);
        assertThat(ctx.getBeanNamesForType(HttpTransport.class))
                .contains("nfyAuthenticatedHttpTransport")
                .doesNotContain("nfyClientHttpTransport");
        assertThat(ctx.getBean(NotifyClient.class)).isNotNull();
    }

    /** ② runner 口径的共存让位（withBean 注册序 = 全量先、client 后，断言同③） */
    @Test
    void coexisting_starters_yield_deterministically_in_runner() {
        new ApplicationContextRunner()
                .withBean(fun.commons.notification4j.autoconfigure.NfyAutoConfiguration.class)
                .withBean(ClientAutoConfiguration.class)
                // 全量 starter 的 transport/门面 Bean 直依赖 ObjectMapper（真实应用由 JacksonAutoConfiguration 提供）
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class,
                        com.fasterxml.jackson.databind.ObjectMapper::new)
                .withPropertyValues(
                        "nfy.runtime.client-enabled=true",
                        "nfy.runtime.mode=remote",
                        "nfy.runtime.remote-url=http://nfy-svc:9200",
                        "nfy.runtime.remote-tenant-id=t_openid",
                        "nfy.runtime.remote-tenant-secret=s3cret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(NotifyClient.class);
                    assertThat(context.getBeanNamesForType(HttpTransport.class))
                            .contains("nfyAuthenticatedHttpTransport")
                            .doesNotContain("nfyClientHttpTransport");
                });
    }

    /**
     * ① 防漂移闸：client-starter 本地化签名（NfyHmacSigner）与 framework4j-signature 的
     * SignatureUtil 逐字节一致——签名串形态与 HMAC-BASE64 值均以框架实现为基准。
     */
    @Test
    void hmac_signer_is_byte_equivalent_with_framework4j_SignatureUtil() {
        String[][] cases = {
                {"POST", "/nfy/api/v1/runtime/messages", "1726000000000", "6f1a2b3c-0000-4000-8000-000000000001",
                        "9e107d9d372bb6826bd81d3542a419d6"},
                {"GET", "/nfy/api/v1/runtime/messages/unread-count", "1", "6f1a2b3c-0000-4000-8000-000000000002", ""},
                {"PUT", "/nfy/api/v1/channels/ch_1", "9999999999999", "nonce-x", "d41d8cd98f00b204e9800998ecf8427e"},
        };
        for (String[] c : cases) {
            String sts = NfyHmacSigner.buildStringToSign(c[0], c[1], c[2], c[3], c[4]);
            assertThat(sts).isEqualTo(SignatureUtil.buildStringToSign(c[0], c[1], c[2], c[3], c[4]));
            assertThat(NfyHmacSigner.sign("s3cret-key", sts))
                    .isEqualTo(SignatureUtil.sign("s3cret-key", sts));
            assertThat(NfyHmacSigner.sign("另一把密钥", sts))
                    .isEqualTo(SignatureUtil.sign("另一把密钥", sts));
        }
    }
}
