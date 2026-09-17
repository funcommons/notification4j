package fun.commons.notification4j.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.transport.HttpTransport;
import fun.commons.notification4j.properties.NfyProperties;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * remote 模式门面：HttpTransport（AuthenticatedHttpTransport：JWT + X-Access-Key 签名头自动注入）
 * 调独立部署 notification4j 的 HTTP 面；tenantId 参数忽略（服务端 token 决定租户，跨模式同代码）。
 * 信封 code!=0 → NfyClientException（与 local 异常面一致）。
 */
@RequiredArgsConstructor
public class RemoteNotifyClient implements NotifyClient {

    private static final String BASE = "/nfy/api/v1";

    private final HttpTransport transport;
    private final NfyProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public SendMessageResult send(long tenantId, SendMessageRequest req) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("type_code", req.typeCode());
        body.put("user_ids", req.userIds());
        body.put("title", req.title());
        body.put("content", req.content());
        if (req.level() != null) body.put("level", req.level());
        if (req.linkUrl() != null) body.put("link_url", req.linkUrl());
        if (req.bizNo() != null) body.put("biz_no", req.bizNo());
        Map<String, Object> data = unwrap("POST " + BASE + "/runtime/messages",
                transport.post(BASE + "/runtime/messages", body, null));
        return new SendMessageResult(
                str(data.get("message_id")), str(data.get("biz_no")),
                (int) num(data.get("receiver_count")), Boolean.TRUE.equals(data.get("inapp_saved")),
                (int) num(data.get("delivery_planned")));
    }

    @Override
    public AnnounceResult announce(long tenantId, AnnounceRequest req) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("title", req.title());
        body.put("content", req.content());
        if (req.level() != null) body.put("level", req.level());
        body.put("need_confirm", req.needConfirm() == null ? 0 : req.needConfirm());
        body.put("effective_at", req.effectiveAtOrDefault());
        body.put("expire_at", req.expireAtOrDefault());
        if (req.bizNo() != null) body.put("biz_no", req.bizNo());
        Map<String, Object> draft = unwrap("POST " + BASE + "/admin/announcements",
                transport.post(BASE + "/admin/announcements", body, null));
        String id = str(draft.get("announcement_id"));
        unwrap("POST " + BASE + "/admin/announcements/" + id + "/publish",
                transport.post(BASE + "/admin/announcements/" + id + "/publish", Map.of(), null));
        return new AnnounceResult(id);
    }

    @Override
    public UnreadSummary unreadCount(long tenantId, String userid) {
        Map<String, Object> data = unwrap("GET " + BASE + "/runtime/messages/unread-count",
                transport.get(BASE + "/runtime/messages/unread-count", null));
        return new UnreadSummary(num(data.get("unread_count")), num(data.get("unconfirmed_count")),
                num(data.get("total")));
    }

    @Override
    public MessagePage listMessages(long tenantId, String userid, String cursor, Integer limit) {
        StringBuilder path = new StringBuilder(BASE + "/runtime/messages");
        String sep = "?";
        if (cursor != null) {
            path.append(sep).append("cursor=").append(cursor);
            sep = "&";
        }
        if (limit != null) {
            path.append(sep).append("limit=").append(limit);
        }
        Map<String, Object> data = unwrap("GET " + path, transport.get(path.toString(), null));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> raw = (List<Map<String, Object>>) data.get("list");
        List<NotifyClient.MessageItemSummary> items = raw == null ? List.of()
                : raw.stream()
                        .map(m -> new MessageItemSummary(str(m.get("message_id")), str(m.get("title")),
                                str(m.get("type_code")), str(m.get("level")), str(m.get("read_status")),
                                m.get("created_at") == null ? null : Long.valueOf(num(m.get("created_at")))))
                        .collect(Collectors.toList());
        return new MessagePage(items, str(data.get("next_cursor")), Boolean.TRUE.equals(data.get("has_more")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrap(String op, Object response) {
        Map<String, Object> envelope = toMap(response, op);
        Object code = envelope.get("code");
        int codeNum = code instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(code));
        if (codeNum != 0) {
            throw new NfyClientException(codeNum, str(envelope.get("message")));
        }
        Object data = envelope.get("data");
        return data instanceof Map ? (Map<String, Object>) data : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object response, String op) {
        if (response instanceof Map) {
            return (Map<String, Object>) response;
        }
        if (response instanceof String s) {
            try {
                return (Map<String, Object>) objectMapper.readValue(s, Map.class);
            } catch (Exception e) {
                throw new IllegalStateException("remote 响应非 JSON: " + op, e);
            }
        }
        return (Map<String, Object>) objectMapper.convertValue(response, Map.class);
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private long num(Object v) {
        return v == null ? 0L : Long.parseLong(String.valueOf(v));
    }
}
