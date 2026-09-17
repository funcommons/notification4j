package fun.commons.notification4j.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.transport.HttpTransport;
import fun.commons.notification4j.client.AnnounceRequest;
import fun.commons.notification4j.client.NfyClientException;
import fun.commons.notification4j.client.NotifyClient;
import fun.commons.notification4j.client.RemoteNotifyClient;
import fun.commons.notification4j.client.SendMessageRequest;
import fun.commons.notification4j.properties.NfyProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 第 8b 步：RemoteNotifyClient 纯单元（fake transport，不起 Spring）。
 * VECTOR: TAG=step8-client-remote
 * 契约：remote 模式 tenantId 参数由服务端 token 决定（客户端忽略）；
 * path=信封 HTTP 面（/runtime/messages 等），code!=0 → NfyClientException；
 * announce=两跳（创建→publish）。
 */
@Tag("step8")
class NfyRemoteClientUnitTest {

    /** fake transport：记录请求，按 path 返回信封 */
    static class FakeTransport implements HttpTransport {
        final List<String> paths = new java.util.ArrayList<>();
        String nextBody = "{\"code\":0,\"data\":{}}";

        private Object envelope() {
            try {
                return new ObjectMapper().readValue(nextBody, Map.class);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Object post(String path, Object body, Map<String, String> headers) {
            paths.add("POST " + path);
            return envelope();
        }

        @Override
        public Object get(String path, Map<String, String> headers) {
            paths.add("GET " + path);
            return envelope();
        }

        @Override
        public Object put(String path, Object body, Map<String, String> headers) {
            paths.add("PUT " + path);
            return envelope();
        }

        @Override
        public Object delete(String path, Map<String, String> headers) {
            paths.add("DELETE " + path);
            return envelope();
        }
    }

    private NotifyClient newClient(FakeTransport transport) {
        NfyProperties props = new NfyProperties();
        props.setMode("remote");
        props.setRemoteUrl("http://nfy-svc:9200");
        return new RemoteNotifyClient(transport, props, new ObjectMapper());
    }

    @Test
    void send_posts_envelope_and_unwraps() {
        FakeTransport t = new FakeTransport();
        t.nextBody = "{\"code\":0,\"data\":{\"message_id\":\"1\",\"biz_no\":\"b\",\"receiver_count\":1,\"inapp_saved\":true,\"delivery_planned\":0}}";
        NotifyClient c = newClient(t);
        NotifyClient.SendMessageResult r = c.send(123L, SendMessageRequest.of(
                "T", List.of("u"), "标题", "内容", "NORMAL", null, "b"));
        assertThat(r.messageId()).isEqualTo("1");
        assertThat(t.paths).containsExactly("POST /nfy/api/v1/runtime/messages");
    }

    @Test
    void announce_is_two_hop_and_unread_is_get() {
        FakeTransport t = new FakeTransport();
        NotifyClient c = newClient(t);
        t.nextBody = "{\"code\":0,\"data\":{\"announcement_id\":\"42\",\"status\":\"DRAFT\"}}";
        NotifyClient.AnnounceResult a = c.announce(1L, AnnounceRequest.of("t", "c", null, 1, null, null));
        assertThat(a.announcementId()).isEqualTo("42");
        t.nextBody = "{\"code\":0,\"data\":{\"status\":\"PUBLISHED\"}}";
        // publish 走 POST /admin/announcements/42/publish
        t.nextBody = "{\"code\":0,\"data\":{\"announcement_id\":\"42\",\"status\":\"PUBLISHED\"}}";
        c.announce(1L, AnnounceRequest.of("t2", "c", null, 0, null, null));
        assertThat(t.paths).contains("POST /nfy/api/v1/admin/announcements", "POST /nfy/api/v1/admin/announcements/42/publish");

        t.nextBody = "{\"code\":0,\"data\":{\"unread_count\":1,\"unconfirmed_count\":0,\"total\":1}}";
        NotifyClient.UnreadSummary u = c.unreadCount(1L, "u_1");
        assertThat(u.total()).isEqualTo(1);
        assertThat(t.paths).contains("GET /nfy/api/v1/runtime/messages/unread-count");

        t.nextBody = "{\"code\":0,\"data\":{\"list\":[],\"next_cursor\":null,\"has_more\":false}}";
        NotifyClient.MessagePage p = c.listMessages(1L, "u_1", null, 10);
        assertThat(p.list()).isEmpty();
    }

    @Test
    void business_error_raises_nfy_client_exception() {
        FakeTransport t = new FakeTransport();
        NotifyClient c = newClient(t);
        t.nextBody = "{\"code\":10601,\"message\":\"消息类型不存在或已停用\",\"data\":null}";
        assertThatThrownBy(() -> c.send(1L, SendMessageRequest.of(
                "T", List.of("u"), "t", "c", null, null, null)))
                .isInstanceOf(NfyClientException.class)
                .hasMessageContaining("10601");
    }
}
