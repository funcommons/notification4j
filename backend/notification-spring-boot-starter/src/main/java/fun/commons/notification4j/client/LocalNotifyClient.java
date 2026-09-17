package fun.commons.notification4j.client;

import fun.commons.notification4j.dto.PostMessagesRequest;
import fun.commons.notification4j.service.AnnouncementAdminService;
import fun.commons.notification4j.service.AnnouncementService;
import fun.commons.notification4j.service.MessageService;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * local 模式门面：进程内直连 service（同 JVM 嵌入形态）。
 * ApiException 等业务异常统一转 NfyClientException（跨模式异常面一致）。
 */
@RequiredArgsConstructor
public class LocalNotifyClient implements NotifyClient {

    private final MessageService messageService;
    private final AnnouncementAdminService announcementAdminService;
    private final AnnouncementService announcementService;

    @Override
    public SendMessageResult send(long tenantId, SendMessageRequest req) {
        PostMessagesRequest dto = new PostMessagesRequest(
                req.bizNo(), req.typeCode(), req.level(), req.userIds(), req.title(), req.content(), req.linkUrl());
        Map<String, Object> data = call(() -> messageService.send(tenantId, dto));
        return new SendMessageResult(
                str(data.get("message_id")), str(data.get("biz_no")),
                (int) num(data.get("receiver_count")), Boolean.TRUE.equals(data.get("inapp_saved")),
                (int) num(data.get("delivery_planned")));
    }

    @Override
    public AnnounceResult announce(long tenantId, AnnounceRequest req) {
        fun.commons.notification4j.dto.PostAnnouncementsRequest create =
                new fun.commons.notification4j.dto.PostAnnouncementsRequest(
                        req.title(), req.content(), req.level(), req.effectiveAtOrDefault(),
                        req.expireAtOrDefault(), req.needConfirm(), null, List.of(), req.bizNo());
        Map<String, Object> draft = call(() -> announcementAdminService.create(tenantId, create));
        String id = str(draft.get("announcement_id"));
        call(() -> announcementAdminService.publish(tenantId, id));
        return new AnnounceResult(id);
    }

    @Override
    public UnreadSummary unreadCount(long tenantId, String userid) {
        long inapp = messageService.unreadInappCount(tenantId, userid);
        long unconfirmed = announcementService.unconfirmedCount(tenantId, userid);
        return new UnreadSummary(inapp, unconfirmed, inapp + unconfirmed);
    }

    @Override
    public MessagePage listMessages(long tenantId, String userid, String cursor, Integer limit) {
        Map<String, Object> data = messageService.list(tenantId, userid, null, null, null, cursor, limit);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> raw = (List<Map<String, Object>>) data.get("list");
        List<NotifyClient.MessageItemSummary> items = raw == null ? List.of()
                : raw.stream()
                        .map(m -> new MessageItemSummary(str(m.get("message_id")), str(m.get("title")),
                                str(m.get("type_code")), str(m.get("level")), str(m.get("read_status")),
                                m.get("created_at") == null ? null : Long.valueOf(num(m.get("created_at")))))
                        .toList();
        return new MessagePage(items, str(data.get("next_cursor")), Boolean.TRUE.equals(data.get("has_more")));
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private long num(Object v) {
        return v == null ? 0L : Long.parseLong(String.valueOf(v));
    }

    /** ApiException → NfyClientException（跨模式异常面一致：code/消息不变） */
    private <T> T call(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (fun.commons.framework4j.web.ApiException e) {
            throw new NfyClientException(e.getCode(), e.getMessage());
        }
    }
}
