package fun.commons.notification4j.client;

import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.dto.PostAnnouncementsRequest;
import fun.commons.notification4j.dto.PostMessagesRequest;
import fun.commons.notification4j.service.AnnouncementAdminService;
import fun.commons.notification4j.service.AnnouncementService;
import fun.commons.notification4j.service.MessageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 26 步 client 层单测：LocalNotifyClient（local 模式进程内门面）。
 * call() 委托：请求 DTO 逐字段透传 / 响应 Map → record 映射（数值型别收敛）/
 * ApiException → NfyClientException（code/消息不变，跨模式异常面一致）。
 */
// VECTOR: TAG=step26-unit
class LocalNotifyClientTest {

    private final MessageService messageService = mock(MessageService.class);
    private final AnnouncementAdminService announcementAdminService = mock(AnnouncementAdminService.class);
    private final AnnouncementService announcementService = mock(AnnouncementService.class);
    private final LocalNotifyClient client =
            new LocalNotifyClient(messageService, announcementAdminService, announcementService);

    // ---- send ----

    @Test
    void send_builds_request_dto_and_maps_result_record() {
        when(messageService.send(eq(1L), any(PostMessagesRequest.class))).thenReturn(Map.of(
                "message_id", "777", "biz_no", "BIZ-1",
                "receiver_count", 2, "inapp_saved", true, "delivery_planned", 3));

        NotifyClient.SendMessageResult r = client.send(1L, SendMessageRequest.of(
                "OTC", List.of("u1"), "标题", "内容", "URGENT", "https://x/y", "BIZ-1"));

        ArgumentCaptor<PostMessagesRequest> cap = ArgumentCaptor.forClass(PostMessagesRequest.class);
        verify(messageService).send(eq(1L), cap.capture());
        assertThat(cap.getValue().typeCode()).isEqualTo("OTC");
        assertThat(cap.getValue().userIds()).isEqualTo(List.of("u1"));
        assertThat(cap.getValue().title()).isEqualTo("标题");
        assertThat(cap.getValue().content()).isEqualTo("内容");
        assertThat(cap.getValue().level()).isEqualTo("URGENT");
        assertThat(cap.getValue().linkUrl()).isEqualTo("https://x/y");
        assertThat(cap.getValue().bizNo()).isEqualTo("BIZ-1");

        assertThat(r.messageId()).isEqualTo("777");
        assertThat(r.bizNo()).isEqualTo("BIZ-1");
        assertThat(r.receiverCount()).isEqualTo(2);
        assertThat(r.inappSaved()).isTrue();
        assertThat(r.deliveryPlanned()).isEqualTo(3);
    }

    @Test
    void send_maps_null_optional_fields_and_missing_numbers_to_defaults() {
        when(messageService.send(anyLong(), any(PostMessagesRequest.class)))
                .thenReturn(Map.of("message_id", 9L, "inapp_saved", Boolean.FALSE));

        NotifyClient.SendMessageResult r = client.send(1L, SendMessageRequest.of(
                "OTC", List.of("u1"), "t", "c", null, null, null));

        assertThat(r.messageId()).isEqualTo("9"); // 数值 message_id → 字符串（跨模式同构）
        assertThat(r.bizNo()).isNull();
        assertThat(r.receiverCount()).isZero(); // 缺失数值 → 0
        assertThat(r.inappSaved()).isFalse(); // 缺失布尔 → false
        assertThat(r.deliveryPlanned()).isZero();
    }

    @Test
    void send_wraps_api_exception_into_nfy_client_exception_with_same_code_and_message() {
        when(messageService.send(anyLong(), any(PostMessagesRequest.class)))
                .thenThrow(new ApiException(10401, "业务号重复，幂等拒绝"));

        assertThatThrownBy(() -> client.send(1L, SendMessageRequest.of(
                "OTC", List.of("u1"), "t", "c", null, null, "B1")))
                .isInstanceOfSatisfying(NfyClientException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10401);
                    assertThat(e.getMessage()).isEqualTo("[10401] 业务号重复，幂等拒绝");
                });
    }

    // ---- announce ----

    @Test
    void announce_creates_then_publishes_and_returns_id() {
        when(announcementAdminService.create(eq(1L), any(PostAnnouncementsRequest.class)))
                .thenReturn(Map.of("announcement_id", "A1"));

        NotifyClient.AnnounceResult r = client.announce(1L, AnnounceRequest.of(
                "公告", "内容", "IMPORTANT", 1, 1000L, "AN-1"));

        ArgumentCaptor<PostAnnouncementsRequest> cap = ArgumentCaptor.forClass(PostAnnouncementsRequest.class);
        verify(announcementAdminService).create(eq(1L), cap.capture());
        assertThat(cap.getValue().title()).isEqualTo("公告");
        assertThat(cap.getValue().level()).isEqualTo("IMPORTANT");
        assertThat(cap.getValue().needConfirm()).isEqualTo(1);
        assertThat(cap.getValue().bizNo()).isEqualTo("AN-1");
        verify(announcementAdminService).publish(1L, "A1"); // 创建后立即发布
        assertThat(r.announcementId()).isEqualTo("A1");
    }

    // ---- unread / list ----

    @Test
    void unread_count_sums_inapp_and_unconfirmed() {
        when(messageService.unreadInappCount(1L, "u1")).thenReturn(3L);
        when(announcementService.unconfirmedCount(1L, "u1")).thenReturn(5L);

        NotifyClient.UnreadSummary s = client.unreadCount(1L, "u1");

        assertThat(s.unreadCount()).isEqualTo(3L);
        assertThat(s.unconfirmedCount()).isEqualTo(5L);
        assertThat(s.total()).isEqualTo(8L);
    }

    @Test
    void list_messages_maps_items_and_pagination_and_defaults_limit_entrypoint() {
        when(messageService.list(eq(1L), eq("u1"), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(Map.of(
                        "list", List.of(Map.of(
                                "message_id", "777", "title", "标题", "type_code", "OTC",
                                "level", "NORMAL", "read_status", "UNREAD", "created_at", 1726500000000L)),
                        "next_cursor", "c2", "has_more", true));

        NotifyClient.MessagePage page = client.listMessages(1L, "u1"); // default 方法（缺省 limit 入口）

        assertThat(page.list()).hasSize(1);
        NotifyClient.MessageItemSummary item = page.list().get(0);
        assertThat(item.messageId()).isEqualTo("777");
        assertThat(item.title()).isEqualTo("标题");
        assertThat(item.readStatus()).isEqualTo("UNREAD");
        assertThat(item.createdAt()).isEqualTo(1726500000000L);
        assertThat(page.nextCursor()).isEqualTo("c2");
        assertThat(page.hasMore()).isTrue();

        // null list → 空页不炸
        when(messageService.list(eq(1L), eq("u2"), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(Map.of("has_more", false));
        assertThat(client.listMessages(1L, "u2").list()).isEmpty();
    }
}
