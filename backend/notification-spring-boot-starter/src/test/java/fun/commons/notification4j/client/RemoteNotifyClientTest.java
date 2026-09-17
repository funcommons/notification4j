package fun.commons.notification4j.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.transport.HttpTransport;
import fun.commons.notification4j.properties.NfyProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.within;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 26 步 client 层单测：RemoteNotifyClient（remote 模式 HTTP 门面，mock HttpTransport 零网络）。
 * 断言面：请求组装（路径常量 / snake_case 字段 / 可选字段缺省不传 / query 拼接）/
 * 信封 unwrap（code!=0 → NfyClientException 与 local 同构；code 为数字与字符串两种型别）/
 * 响应形态（Map 直收 / JSON 字符串解析 / 非 JSON 报错 / data 缺省空 Map）。
 * <p>
 * 登记不可测部分：resilience4j 重试/熔断装配在 autoconfig 层（NfyAutoConfiguration 按
 * spring 环境条件装配，登记为 IT/装配期覆盖），本类自身无重试分支——mock transport 直连。
 */
// VECTOR: TAG=step26-unit
class RemoteNotifyClientTest {

    private final HttpTransport transport = mock(HttpTransport.class);
    private final NfyProperties properties = new NfyProperties();
    private final RemoteNotifyClient client =
            new RemoteNotifyClient(transport, properties, new ObjectMapper());

    private static Map<String, Object> ok(Map<String, Object> data) {
        Map<String, Object> envelope = new java.util.HashMap<>();
        envelope.put("code", 0);
        envelope.put("message", "ok");
        if (data != null) {
            envelope.put("data", data);
        }
        return envelope;
    }

    // ---- send ----

    @Test
    void send_posts_snake_case_body_to_runtime_path_and_maps_result() {
        when(transport.post(eq("/nfy/api/v1/runtime/messages"), any(), isNull()))
                .thenReturn(ok(Map.of("message_id", "777", "biz_no", "BIZ-1",
                        "receiver_count", 2, "inapp_saved", true, "delivery_planned", 3)));

        NotifyClient.SendMessageResult r = client.send(1L, SendMessageRequest.of(
                "OTC", List.of("u1", "u2"), "标题", "内容", "URGENT", "https://x", "BIZ-1"));

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(transport).post(eq("/nfy/api/v1/runtime/messages"), body.capture(), isNull());
        assertThat(body.getValue()).isEqualTo(Map.of(
                "type_code", "OTC", "user_ids", List.of("u1", "u2"),
                "title", "标题", "content", "内容",
                "level", "URGENT", "link_url", "https://x", "biz_no", "BIZ-1"));
        assertThat(r.messageId()).isEqualTo("777");
        assertThat(r.receiverCount()).isEqualTo(2);
        assertThat(r.inappSaved()).isTrue();
        assertThat(r.deliveryPlanned()).isEqualTo(3);
    }

    @Test
    void send_omits_optional_fields_when_null() {
        when(transport.post(anyString(), any(), isNull()))
                .thenReturn(ok(Map.of("message_id", "1")));

        client.send(1L, SendMessageRequest.of("OTC", List.of("u1"), "t", "c", null, null, null));

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(transport).post(anyString(), body.capture(), isNull());
        @SuppressWarnings("unchecked")
        Map<String, Object> sent = (Map<String, Object>) body.getValue();
        assertThat(sent).doesNotContainKeys("level", "link_url", "biz_no");
    }

    @Test
    void send_envelope_nonzero_code_throws_nfy_client_exception_with_string_code_support() {
        // code 为 Integer
        when(transport.post(anyString(), any(), isNull()))
                .thenReturn(Map.of("code", 10401, "message", "业务号重复"));
        assertThatThrownBy(() -> client.send(1L, SendMessageRequest.of(
                "OTC", List.of("u1"), "t", "c", null, null, "B1")))
                .isInstanceOfSatisfying(NfyClientException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10401);
                    assertThat(e.getMessage()).isEqualTo("[10401] 业务号重复");
                });

        // code 为字符串型别（远端反序列化差异）
        when(transport.post(anyString(), any(), isNull()))
                .thenReturn(Map.of("code", "10207", "message", "token 型别不匹配"));
        assertThatThrownBy(() -> client.send(1L, SendMessageRequest.of(
                "OTC", List.of("u1"), "t", "c", null, null, "B1")))
                .isInstanceOfSatisfying(NfyClientException.class, e -> assertThat(e.getCode()).isEqualTo(10207));
    }

    @Test
    void send_accepts_raw_json_string_response() {
        when(transport.post(anyString(), any(), isNull()))
                .thenReturn("{\"code\":0,\"message\":\"ok\",\"data\":{\"message_id\":\"777\"}}");

        NotifyClient.SendMessageResult r = client.send(1L, SendMessageRequest.of(
                "OTC", List.of("u1"), "t", "c", null, null, null));

        assertThat(r.messageId()).isEqualTo("777");
    }

    @Test
    void send_non_json_string_response_is_rejected_with_op_context() {
        when(transport.post(anyString(), any(), isNull())).thenReturn("<html>502</html>");

        assertThatThrownBy(() -> client.send(1L, SendMessageRequest.of(
                "OTC", List.of("u1"), "t", "c", null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POST /nfy/api/v1/runtime/messages"); // 携带操作上下文
    }

    // ---- announce ----

    @Test
    void announce_creates_then_publishes_with_id_path() {
        when(transport.post(eq("/nfy/api/v1/admin/announcements"), any(), isNull()))
                .thenReturn(ok(Map.of("announcement_id", "A1")));
        when(transport.post(eq("/nfy/api/v1/admin/announcements/A1/publish"), any(), isNull()))
                .thenReturn(ok(Map.of()));

        AnnounceRequest req = AnnounceRequest.of("公告", "内容", "URGENT", 1, 1000L, "AN-1");
        NotifyClient.AnnounceResult r = client.announce(1L, req);

        ArgumentCaptor<Object> createBody = ArgumentCaptor.forClass(Object.class);
        verify(transport).post(eq("/nfy/api/v1/admin/announcements"), createBody.capture(), isNull());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) createBody.getValue();
        assertThat(body.get("title")).isEqualTo("公告");
        assertThat(body.get("content")).isEqualTo("内容");
        assertThat(body.get("level")).isEqualTo("URGENT");
        assertThat(body.get("need_confirm")).isEqualTo(1); // needConfirm 非空直传
        assertThat(body.get("effective_at")).isEqualTo(1000L); // 显式生效时间直传
        assertThat(((Number) body.get("expire_at")).longValue()) // expire 缺省 = now+7d（毫秒口径）
                .isCloseTo(OffsetDateTime.now().plusDays(7).toInstant().toEpochMilli(),
                        within(5000L));
        assertThat(body.get("biz_no")).isEqualTo("AN-1");
        verify(transport).post(eq("/nfy/api/v1/admin/announcements/A1/publish"), eq(Map.of()), isNull());
        assertThat(r.announcementId()).isEqualTo("A1");
    }

    // ---- unread / list ----

    @Test
    void unread_count_ignores_tenant_argument_and_uses_fixed_get_path() {
        when(transport.get(eq("/nfy/api/v1/runtime/messages/unread-count"), isNull()))
                .thenReturn(ok(Map.of("unread_count", 3, "unconfirmed_count", 5, "total", 8)));

        NotifyClient.UnreadSummary s = client.unreadCount(42L, "u1"); // tenantId 由服务端 token 决定

        assertThat(s.unreadCount()).isEqualTo(3L);
        assertThat(s.unconfirmedCount()).isEqualTo(5L);
        assertThat(s.total()).isEqualTo(8L);
    }

    @Test
    void unread_count_envelope_without_data_maps_to_zero_summary() {
        when(transport.get(anyString(), isNull()))
                .thenReturn(Map.of("code", 0, "message", "ok")); // 无 data

        NotifyClient.UnreadSummary s = client.unreadCount(1L, "u1");
        assertThat(s.unreadCount()).isZero();
        assertThat(s.total()).isZero();
    }

    @Test
    void list_messages_builds_query_string_from_cursor_and_limit() {
        when(transport.get(eq("/nfy/api/v1/runtime/messages?cursor=c1&limit=5"), isNull()))
                .thenReturn(ok(Map.of(
                        "list", List.of(Map.of("message_id", "7", "title", "t", "created_at", "1726500000000")),
                        "next_cursor", "c2", "has_more", true)));

        NotifyClient.MessagePage page = client.listMessages(1L, "u1", "c1", 5);

        assertThat(page.list()).hasSize(1);
        assertThat(page.list().get(0).messageId()).isEqualTo("7");
        assertThat(page.list().get(0).createdAt()).isEqualTo(1726500000000L); // 字符串时间戳 → Long
        assertThat(page.nextCursor()).isEqualTo("c2");
        assertThat(page.hasMore()).isTrue();

        // 无 cursor/limit → 纯路径无 query
        when(transport.get(eq("/nfy/api/v1/runtime/messages"), isNull())).thenReturn(ok(Map.of()));
        assertThat(client.listMessages(1L, "u1", null, null).list()).isEmpty();
    }
}
