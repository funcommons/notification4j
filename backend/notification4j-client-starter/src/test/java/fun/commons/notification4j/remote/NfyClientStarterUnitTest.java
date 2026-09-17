package fun.commons.notification4j.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.transport.HttpTransport;
import fun.commons.notification4j.client.AnnounceRequest;
import fun.commons.notification4j.client.NotifyClient;
import fun.commons.notification4j.client.SendMessageRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 编码第 30 步：client-starter 自身单测（ApplicationContextRunner，无容器/无网络）。
 * 契约：client-enabled 开闸、remote-url 缺失 fail-fast、mode=local 拒绝、
 * token generator Bean 缺席降级仅签名（四元组在）、未配租户凭据不签名不 NPE（第 26 步 P1 回归）、
 * 业务方自定义 NotifyClient 让位、超时接线、签名器自洽。
 * 跨模块断言（SignatureUtil 逐字节等价、双 starter 共存让位、imports 真实注册）在
 * notification4j-it 的 NfyClientStarterTest——等价对照需要 framework4j-all（SignatureUtil）在 classpath。
 */
class NfyClientStarterUnitTest {

    /** 录制型 fake transport：记录最后一次请求，按约定信封应答 */
    static class RecordingTransport implements HttpTransport {
        String method;
        String path;
        Object body;
        Map<String, String> headers;
        Map<String, Object> envelope = Map.of("code", 0, "data", Map.of(
                "message_id", "1", "biz_no", "b1", "receiver_count", 2,
                "inapp_saved", true, "delivery_planned", 0));

        private void record(String method, String path, Object body, Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.body = body;
            this.headers = headers;
        }

        @Override
        public Object post(String url, Object body, Map<String, String> headers) {
            record("POST", url, body, headers);
            return envelope;
        }

        @Override
        public Object get(String url, Map<String, String> headers) {
            record("GET", url, null, headers);
            return envelope;
        }

        @Override
        public Object put(String url, Object body, Map<String, String> headers) {
            record("PUT", url, body, headers);
            return envelope;
        }

        @Override
        public Object delete(String url, Map<String, String> headers) {
            record("DELETE", url, null, headers);
            return envelope;
        }
    }

    /** 最小开闸 runner（withBean 注册 = Boot 3.2.x 无 AutoConfigurations 工具的等价编排） */
    private static ApplicationContextRunner runner(String... extra) {
        return new ApplicationContextRunner()
                .withBean(ClientAutoConfiguration.class)
                .withPropertyValues(extra);
    }

    /** 配齐 remote-url + 凭据的基准 runner */
    private static ApplicationContextRunner fullRunner(String... extra) {
        java.util.List<String> props = new java.util.ArrayList<>(java.util.List.of(
                "nfy.runtime.client-enabled=true",
                "nfy.runtime.mode=remote",
                "nfy.runtime.remote-url=http://nfy-svc:9200",
                "nfy.runtime.remote-tenant-id=t_openid",
                "nfy.runtime.remote-tenant-secret=s3cret"));
        props.addAll(java.util.List.of(extra));
        return runner(props.toArray(new String[0]));
    }

    /** ① 配齐 remote-url + 凭据 → NotifyClient Bean 存在，send 走 mock transport 正确组包 */
    @Test
    void wired_client_sends_snake_case_body_via_injected_transport() {
        RecordingTransport transport = new RecordingTransport();
        fullRunner().withBean(HttpTransport.class, () -> transport).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(NotifyClient.class);
            assertThat(ctx.getBean(NotifyClient.class)).isInstanceOf(RemoteNotifyClient.class);

            NotifyClient.SendMessageResult r = ctx.getBean(NotifyClient.class).send(123L,
                    SendMessageRequest.of("T", List.of("u1", "u2"), "标题", "内容", "NORMAL", null, "b1"));
            assertThat(r.messageId()).isEqualTo("1");
            assertThat(r.receiverCount()).isEqualTo(2);
            assertThat(transport.method).isEqualTo("POST");
            assertThat(transport.path).isEqualTo("/nfy/api/v1/runtime/messages");
            assertThat(transport.body)
                    .isInstanceOfSatisfying(Map.class, body -> assertThat(body)
                            .containsEntry("type_code", "T")
                            .containsEntry("user_ids", List.of("u1", "u2"))
                            .containsEntry("title", "标题")
                            .containsEntry("content", "内容")
                            .containsEntry("level", "NORMAL")
                            .containsEntry("biz_no", "b1"));
        });
    }

    /** ①b 门面其余路径：announce 两跳、unreadCount、listMessages、错误信封（与全量 starter 契约同构） */
    @Test
    void facade_remaining_paths_pack_and_unpack() {
        RecordingTransport transport = new RecordingTransport();
        transport.envelope = Map.of("code", 0, "data", Map.of("announcement_id", "42"));
        RemoteNotifyClient client = new RemoteNotifyClient(transport, new NfyClientProperties(), new ObjectMapper());

        NotifyClient.AnnounceResult a = client.announce(1L, AnnounceRequest.of("t", "c", null, 1, null, "b-1"));
        assertThat(a.announcementId()).isEqualTo("42");
        assertThat(transport.method).isEqualTo("POST");
        assertThat(transport.path).isEqualTo("/nfy/api/v1/admin/announcements/42/publish");

        transport.envelope = Map.of("code", 0, "data",
                Map.of("unread_count", 1, "unconfirmed_count", 0, "total", 1));
        NotifyClient.UnreadSummary u = client.unreadCount(1L, "u1");
        assertThat(u.total()).isEqualTo(1);
        assertThat(transport.path).isEqualTo("/nfy/api/v1/runtime/messages/unread-count");

        transport.envelope = Map.of("code", 0, "data",
                Map.of("list", List.of(Map.of("message_id", "m1", "title", "标题", "type_code", "TC",
                        "level", "NORMAL", "read_status", "UNREAD", "created_at", 123L)),
                        "next_cursor", "c2", "has_more", true));
        NotifyClient.MessagePage p = client.listMessages(1L, "u1", "c1", 10);
        assertThat(p.list()).hasSize(1);
        assertThat(p.list().get(0).createdAt()).isEqualTo(123L);
        assertThat(p.nextCursor()).isEqualTo("c2");
        assertThat(p.hasMore()).isTrue();

        // 错误信封 → NfyClientException（code!=0）
        transport.envelope = Map.of("code", 10601, "message", "消息类型不存在或已停用");
        assertThatThrownBy(() -> client.send(1L, SendMessageRequest.of("T", List.of("u"), "t", "c", null, null, null)))
                .isInstanceOf(fun.commons.notification4j.client.NfyClientException.class)
                .hasMessageContaining("10601");
    }

    /** ② 缺 remote-url → 启动 fail-fast（Assert 消息） */
    @Test
    void missing_remote_url_fails_fast() {
        runner("nfy.runtime.client-enabled=true").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure())
                    .isInstanceOf(org.springframework.beans.factory.BeanCreationException.class)
                    .hasStackTraceContaining("nfy.runtime.remote-url 必填")
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        });
    }

    /** mode=local 显式拒绝（client-starter 无数据面，指向全量 starter） */
    @Test
    void local_mode_rejected() {
        runner("nfy.runtime.client-enabled=true", "nfy.runtime.mode=local",
                "nfy.runtime.remote-url=http://nfy-svc:9200").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure())
                    .hasStackTraceContaining("mode=local 不受 notification4j-client-starter 支持")
                    .hasRootCauseInstanceOf(IllegalStateException.class);
        });
    }

    /**
     * ③ token 供源缺席（supplier=null ≡ accesstoken 类/Bean 缺席）→ 降级仅签名：
     * HMAC 四元组在，无 Authorization。断言对象 = AuthenticatedHttpTransport 装饰 recording
     * delegate（与装配自建结构同构；直接注裸 transport 无装饰层，headers 恒为 null 不可断言）。
     */
    @Test
    void token_supplier_absent_degrades_to_signature_only() {
        RecordingTransport delegate = new RecordingTransport();
        NfyClientProperties props = new NfyClientProperties();
        props.setRemoteUrl("http://nfy-svc:9200");
        props.setRemoteTenantId("t_openid");
        props.setRemoteTenantSecret("s3cret");
        AuthenticatedHttpTransport decorated =
                new AuthenticatedHttpTransport(delegate, null, props, new ObjectMapper());
        fullRunner().withBean(HttpTransport.class, () -> decorated).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            ctx.getBean(NotifyClient.class).send(1L,
                    SendMessageRequest.of("T", List.of("u1"), "t", "c", null, null, null));
            assertThat(delegate.headers)
                    .containsKeys("X-Access-Key", "X-Timestamp", "X-Nonce", "X-Signature")
                    .doesNotContainKey("Authorization");
            assertThat(delegate.headers.get("X-Access-Key")).isEqualTo("t_openid");
            // 签名串路径口：去 remote-url 前缀 + 去 query
            assertThat(delegate.path).isEqualTo("/nfy/api/v1/runtime/messages");
        });
    }

    /** ③b 无 mock transport 时装配自建 AuthenticatedHttpTransport（client-starter 独立可用） */
    @Test
    void client_builds_own_authenticated_transport_when_absent() {
        fullRunner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(HttpTransport.class)).isInstanceOf(AuthenticatedHttpTransport.class);
            assertThat(ctx.getBean(NfyClientProperties.class).getRemoteUrl()).isEqualTo("http://nfy-svc:9200");
        });
    }

    /** ④ 未配租户凭据 → 不签名不 NPE（第 26 步 P1 修复回归） */
    @Test
    void missing_tenant_credentials_skips_signature_without_npe() {
        RecordingTransport delegate = new RecordingTransport();
        NfyClientProperties props = new NfyClientProperties();
        props.setRemoteUrl("http://nfy-svc:9200");   // remote-tenant-id/secret 均缺省
        AuthenticatedHttpTransport decorated =
                new AuthenticatedHttpTransport(delegate, null, props, new ObjectMapper());
        runner("nfy.runtime.client-enabled=true",
                "nfy.runtime.mode=remote",
                "nfy.runtime.remote-url=http://nfy-svc:9200")
                .withBean(HttpTransport.class, () -> decorated)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    ctx.getBean(NotifyClient.class).send(1L,
                            SendMessageRequest.of("T", List.of("u1"), "t", "c", null, null, null));
                    assertThat(delegate.headers)
                            .doesNotContainKeys("X-Access-Key", "X-Timestamp", "X-Nonce", "X-Signature",
                                    "Authorization");
                    assertThat(delegate.path).isEqualTo("/nfy/api/v1/runtime/messages");
                });
    }

    /** ⑤ 业务方自定义 NotifyClient Bean → @ConditionalOnMissingBean 让位（缺 remote-url 也不 fail） */
    @Test
    void user_defined_notify_client_wins_over_autoconfigured() {
        NotifyClient custom = new NotifyClient() {
            @Override public SendMessageResult send(long tenantId, SendMessageRequest req) { return null; }
            @Override public AnnounceResult announce(long tenantId, AnnounceRequest req) { return null; }
            @Override public UnreadSummary unreadCount(long tenantId, String userid) { return null; }
            @Override public MessagePage listMessages(long tenantId, String userid, String cursor, Integer limit) { return null; }
        };
        runner("nfy.runtime.client-enabled=true")
                .withBean("customNotifyClient", NotifyClient.class, () -> custom)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();   // 让位发生在 remote-url Assert 之前
                    assertThat(ctx.getBean(NotifyClient.class)).isSameAs(custom);
                });
    }

    /** ⑦ delegate RestTemplate 超时接线：缺席 → 新建 + properties 注入（yml 松散命名绑定）；唯一 Bean → 原样复用 */
    @Test
    void delegate_rest_template_timeout_wiring() throws Exception {
        fullRunner("nfy.runtime.connect-timeout-ms=7000", "nfy.runtime.read-timeout-ms=9000").run(ctx -> {
            NfyClientProperties props = ctx.getBean(NfyClientProperties.class);
            assertThat(props.getConnectTimeoutMs()).isEqualTo(7000);
            assertThat(props.getReadTimeoutMs()).isEqualTo(9000);

            ObjectProvider<RestTemplate> absent = mock(ObjectProvider.class);
            when(absent.getIfUnique()).thenReturn(null);
            RestTemplate built = ClientAutoConfiguration.resolveRestTemplate(absent, props);
            assertThat(built.getRequestFactory()).isInstanceOf(SimpleClientHttpRequestFactory.class);
            // Spring 6.1 的 SimpleClientHttpRequestFactory 只有 setter，读私有字段断言
            assertThat(intField(built.getRequestFactory(), "connectTimeout")).isEqualTo(7000);
            assertThat(intField(built.getRequestFactory(), "readTimeout")).isEqualTo(9000);

            RestTemplate owned = new RestTemplate();
            ObjectProvider<RestTemplate> unique = mock(ObjectProvider.class);
            when(unique.getIfUnique()).thenReturn(owned);
            assertThat(ClientAutoConfiguration.resolveRestTemplate(unique, props)).isSameAs(owned);
        });
    }

    private static Integer intField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(target);
    }

    /** ⑧ 签名器自洽：签名串精确形态 + 同参重放一致 + 空密钥显式失败 */
    @Test
    void hmac_signer_self_consistent() {
        String sts = NfyHmacSigner.buildStringToSign("POST", "/nfy/api/v1/runtime/messages",
                "1726000000000", "nonce-1", "0abc");
        assertThat(sts).isEqualTo("POST\n/nfy/api/v1/runtime/messages\n1726000000000\nnonce-1\n0abc");
        String once = NfyHmacSigner.sign("s3cret", sts);
        assertThat(NfyHmacSigner.sign("s3cret", sts)).isEqualTo(once);
        assertThatThrownBy(() -> NfyHmacSigner.sign("", sts))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }
}
