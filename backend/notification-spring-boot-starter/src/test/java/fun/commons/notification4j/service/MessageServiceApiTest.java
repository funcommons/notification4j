package fun.commons.notification4j.service;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.dto.PostMessagesRequest;
import fun.commons.notification4j.entity.NfyaDelivery;
import fun.commons.notification4j.entity.NfyaMessage;
import fun.commons.notification4j.entity.NfyaMessageRecipient;
import fun.commons.notification4j.entity.NfyaMessageType;
import fun.commons.notification4j.mapper.NfyaDeliveryMapper;
import fun.commons.notification4j.mapper.NfyaMessageMapper;
import fun.commons.notification4j.mapper.NfyaMessageRecipientMapper;
import fun.commons.notification4j.mapper.NfyaMessageTypeMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 25 步 service 层 mock 单测：MessageService（发送/未读数/结果查询/撤回/详情已读/列表/批量已读）。
 * mock 四依赖 + 反射注入 baseMapper；Db.saveBatch 静态工具以 mockStatic 拦截（3.5.7 真实现需
 * SqlSessionFactory 上下文，纯单测不可达——IT 已覆盖真批量口径）；LambdaUpdateWrapper#set 路径
 * 预初始化实体 TableInfo（ServiceMockSupport）。分批已读以「500 行短批」驱动两次迭代。
 */
// VECTOR: TAG=step25-unit
class MessageServiceApiTest {

    private final NfyaMessageMapper messageMapper = mock(NfyaMessageMapper.class);
    private final NfyaMessageRecipientMapper recipientMapper = mock(NfyaMessageRecipientMapper.class);
    private final NfyaDeliveryMapper deliveryMapper = mock(NfyaDeliveryMapper.class);
    private final DeliveryPlanService planService = mock(DeliveryPlanService.class);

    @BeforeAll
    static void initTableInfos() {
        ServiceMockSupport.initTableInfo(NfyaMessage.class, NfyaMessageRecipient.class,
                NfyaDelivery.class, fun.commons.notification4j.entity.NfyaAnnouncement.class);
    }

    /** 真实 MessageTypeService（内部 mapper 也 mock），requireEnabled 查询返回给定类型 */
    private MessageTypeService typeServiceReturning(NfyaMessageType type) {
        MessageTypeService ts = new MessageTypeService(new ObjectMapper());
        NfyaMessageTypeMapper tm = mock(NfyaMessageTypeMapper.class);
        ServiceMockSupport.injectBaseMapper(ts, tm);
        when(tm.selectOne(any(), anyBoolean())).thenReturn(type);
        when(tm.selectOne(any())).thenReturn(type);
        return ts;
    }

    private NfyaMessageType enabledType() {
        NfyaMessageType t = new NfyaMessageType();
        t.setTenantId(1L);
        t.setTypeCode("OTC");
        t.setStatus("ENABLED");
        t.setDefaultChannels("[\"INAPP\"]");
        return t;
    }

    private MessageService newService(MessageTypeService typeService) {
        return ServiceMockSupport.injectBaseMapper(
                new MessageService(recipientMapper, typeService, planService, deliveryMapper), messageMapper);
    }

    private static PostMessagesRequest req(String bizNo, List<String> userIds) {
        return new PostMessagesRequest(bizNo, "OTC", "NORMAL", userIds, "标题", "内容", null);
    }

    // ---- send ----

    @Test
    void send_persists_message_batches_recipients_and_plans_delivery() {
        when(messageMapper.insert(any(NfyaMessage.class))).thenAnswer(inv -> {
            inv.<NfyaMessage>getArgument(0).setId(777L);
            return 1;
        });
        when(planService.planForMessage(anyLong(), any(), any(), any(), any(), any())).thenReturn(3);
        MessageTypeService ts = typeServiceReturning(enabledType());
        MessageService service = newService(ts);

        Map<String, Object> data;
        try (var db = mockStatic(Db.class)) {
            db.when(() -> Db.saveBatch(anyCollection())).thenReturn(true);
            data = service.send(1L, req("BIZ-1", List.of("u1", "u2")));
        }

        ArgumentCaptor<NfyaMessage> cap = ArgumentCaptor.forClass(NfyaMessage.class);
        verify(messageMapper).insert(cap.capture());
        NfyaMessage m = cap.getValue();
        assertThat(m.getBizNo()).isEqualTo("BIZ-1");        assertThat(m.getLevel()).isEqualTo("NORMAL"); // level 缺省
        assertThat(m.getLinkUrl()).isEmpty(); // linkUrl 缺省
        assertThat(m.getReceiverCount()).isEqualTo(2);
        assertThat(m.getStatus()).isEqualTo("SENT");
        assertThat(m.getSender()).isEqualTo("API");
        assertThat(data).containsEntry("message_id", "777").containsEntry("biz_no", "BIZ-1")
                .containsEntry("receiver_count", 2).containsEntry("inapp_saved", true)
                .containsEntry("delivery_planned", 3);
        verify(planService).planForMessage(1L, 777L, "OTC", "NORMAL", "标题", List.of("u1", "u2"));
    }

    @Test
    void send_blank_biz_no_gets_generated_uuid() {
        when(messageMapper.insert(any(NfyaMessage.class))).thenAnswer(inv -> {
            inv.<NfyaMessage>getArgument(0).setId(1L);
            return 1;
        });
        when(planService.planForMessage(anyLong(), any(), any(), any(), any(), any())).thenReturn(0);
        try (var db = mockStatic(Db.class)) {
            db.when(() -> Db.saveBatch(anyCollection())).thenReturn(true);
            newService(typeServiceReturning(enabledType())).send(1L, req("  ", List.of("u1")));
        }
        ArgumentCaptor<NfyaMessage> cap = ArgumentCaptor.forClass(NfyaMessage.class);
        verify(messageMapper).insert(cap.capture());
        assertThat(cap.getValue().getBizNo()).matches("[0-9a-f]{32}"); // UUID 去横线小写（第 26 步口径统一：与 BatchJob 一致）
    }

    @Test
    void send_dedups_user_ids_for_receiver_count() {
        when(messageMapper.insert(any(NfyaMessage.class))).thenAnswer(inv -> {
            inv.<NfyaMessage>getArgument(0).setId(1L);
            return 1;
        });
        when(planService.planForMessage(anyLong(), any(), any(), any(), any(), any())).thenReturn(0);
        try (var db = mockStatic(Db.class)) {
            db.when(() -> Db.saveBatch(anyCollection())).thenReturn(true);
            newService(typeServiceReturning(enabledType())).send(1L, req(null, List.of("u1", "u1", "u2")));
        }
        verify(recipientMapper, never()).insert(any(NfyaMessageRecipient.class)); // 批量路径不走逐条
        ArgumentCaptor<NfyaMessage> mcap = ArgumentCaptor.forClass(NfyaMessage.class);
        verify(messageMapper).insert(mcap.capture());
        assertThat(mcap.getValue().getReceiverCount()).isEqualTo(2); // 去重后
    }

    @Test
    void send_empty_user_ids_throws_10101() {
        MessageService service = newService(typeServiceReturning(enabledType()));
        assertThatThrownBy(() -> service.send(1L, req("B", List.of())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10101);
                    assertThat(e.getMessage()).contains("不能为空");
                });
    }

    @Test
    void send_over_1000_user_ids_throws_10102() {
        List<String> users = java.util.stream.IntStream.range(0, 1001)
                .mapToObj(i -> "u" + i).collect(Collectors.toList());
        MessageService service = newService(typeServiceReturning(enabledType()));
        assertThatThrownBy(() -> service.send(1L, req(null, users)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10102);
                    assertThat(e.getMessage()).contains("1000");
                });
    }

    @Test
    void send_duplicate_biz_no_throws_10401() {
        when(messageMapper.insert(any(NfyaMessage.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk"));
        MessageService service = newService(typeServiceReturning(enabledType()));
        assertThatThrownBy(() -> service.send(1L, req("DUP", List.of("u1"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10401);
                    assertThat(e.getMessage()).contains("幂等拒绝");
                });
    }

    @Test
    void send_type_missing_or_disabled_throws_10601_and_short_circuits() {
        NfyaMessageType disabled = enabledType();
        disabled.setStatus("DISABLED");
        MessageService s1 = newService(typeServiceReturning(disabled));
        assertThatThrownBy(() -> s1.send(1L, req(null, List.of("u1"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10601));
        MessageService s2 = newService(typeServiceReturning(null));
        assertThatThrownBy(() -> s2.send(1L, req(null, List.of("u1"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10601));
        verify(planService, never()).planForMessage(anyLong(), any(), any(), any(), any(), any());
    }

    // ---- unreadInappCount ----

    @Test
    void unread_count_returns_mapper_count() {
        when(recipientMapper.selectCount(any())).thenReturn(7L);
        assertThat(newService(typeServiceReturning(null)).unreadInappCount(1L, "u1")).isEqualTo(7L);
    }

    @Test
    void unread_count_null_maps_to_zero() {
        when(recipientMapper.selectCount(any())).thenReturn(null);
        assertThat(newService(typeServiceReturning(null)).unreadInappCount(1L, "u1")).isZero();
    }

    // ---- sendResults ----

    @Test
    void send_results_maps_read_count_and_deliveries() {
        NfyaMessage m = new NfyaMessage();
        m.setId(5L);
        m.setTenantId(1L);
        m.setBizNo("BIZ");
        m.setTypeCode("OTC");
        m.setStatus("SENT");
        m.setReceiverCount(3);
        m.setCreatedAt(OffsetDateTime.parse("2026-06-01T00:00Z"));
        when(messageMapper.selectOne(any(), anyBoolean())).thenReturn(m);

        NfyaMessageRecipient r1 = new NfyaMessageRecipient();
        r1.setReadStatus("READ");
        NfyaMessageRecipient r2 = new NfyaMessageRecipient();
        r2.setReadStatus("UNREAD");
        NfyaMessageRecipient r3 = new NfyaMessageRecipient();
        r3.setReadStatus("READ");
        when(recipientMapper.selectList(any())).thenReturn(List.of(r1, r2, r3));

        NfyaDelivery d1 = new NfyaDelivery();
        d1.setUserid("u1");
        d1.setChannelType("EMAIL");
        d1.setStatus("SUCCESS");
        d1.setSentAt(OffsetDateTime.parse("2026-06-01T01:00Z"));
        NfyaDelivery d2 = new NfyaDelivery();
        d2.setUserid("u2");
        d2.setChannelType("DINGTALK");
        d2.setStatus("PENDING");
        when(deliveryMapper.selectList(any())).thenReturn(List.of(d1, d2));

        Map<String, Object> data = newService(typeServiceReturning(null)).sendResults(1L, "BIZ");
        assertThat(data).containsEntry("message_id", "5").containsEntry("read_count", 2L)
                .containsEntry("receiver_count", 3).containsEntry("created_at",
                        OffsetDateTime.parse("2026-06-01T00:00Z").toInstant().toEpochMilli());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deliveries = (List<Map<String, Object>>) data.get("deliveries");
        assertThat(deliveries).hasSize(2);
        assertThat(deliveries.get(0)).containsEntry("sent_at",
                OffsetDateTime.parse("2026-06-01T01:00Z").toInstant().toEpochMilli());
        assertThat(deliveries.get(1)).containsEntry("sent_at", null).containsEntry("status", "PENDING");
    }

    @Test
    void send_results_missing_biz_no_throws_10400() {
        when(messageMapper.selectOne(any(), anyBoolean())).thenReturn(null);
        assertThatThrownBy(() -> newService(typeServiceReturning(null)).sendResults(1L, "NOPE"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
    }

    // ---- cancel ----

    @Test
    void cancel_sent_message_cascades_pending_deliveries() {
        NfyaMessage m = new NfyaMessage();
        m.setId(5L);
        m.setTenantId(1L);
        m.setStatus("SENT");
        when(messageMapper.selectById(5L)).thenReturn(m);
        when(messageMapper.update(any(), any())).thenReturn(1);
        when(deliveryMapper.update(any(), any())).thenReturn(2);

        var data = newService(typeServiceReturning(null)).cancel(1L, "5");
        assertThat(data).containsEntry("message_id", "5").containsEntry("status", "CANCELLED")
                .containsEntry("cancelled_deliveries", 2);
        verify(deliveryMapper).update(any(), any());
    }

    @Test
    void cancel_already_cancelled_is_idempotent_without_cascade() {
        NfyaMessage m = new NfyaMessage();
        m.setId(5L);
        m.setTenantId(1L);
        m.setStatus("CANCELLED");
        when(messageMapper.selectById(5L)).thenReturn(m);
        when(messageMapper.update(any(), any())).thenReturn(0); // 条件更新 status='SENT' 未命中

        var data = newService(typeServiceReturning(null)).cancel(1L, "5");
        assertThat(data.get("cancelled_deliveries")).isEqualTo(0);
        verify(deliveryMapper, never()).update(any(), any());
    }

    @Test
    void cancel_rejects_non_numeric_missing_and_cross_tenant_with_10400() {
        MessageService service = newService(typeServiceReturning(null));
        assertThatThrownBy(() -> service.cancel(1L, "abc"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
        when(messageMapper.selectById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.cancel(1L, "9"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
        NfyaMessage m = new NfyaMessage();
        m.setId(9L);
        m.setTenantId(2L);
        when(messageMapper.selectById(9L)).thenReturn(m);
        assertThatThrownBy(() -> service.cancel(1L, "9"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
    }

    // ---- detailAndMarkRead ----

    @Test
    void detail_unread_recipient_marks_read() {
        NfyaMessage m = new NfyaMessage();
        m.setId(5L);
        m.setTenantId(1L);
        m.setTitle("T");
        m.setContent("C");
        m.setTypeCode("OTC");
        m.setLevel("URGENT");
        m.setLinkUrl("https://x");
        m.setStatus("SENT");
        when(messageMapper.selectById(5L)).thenReturn(m);
        NfyaMessageRecipient r = new NfyaMessageRecipient();
        r.setReadStatus("UNREAD");
        when(recipientMapper.selectOne(any())).thenReturn(r); // detailAndMarkRead 直调单参 selectOne
        when(recipientMapper.selectOne(any(), anyBoolean())).thenReturn(r);
        when(recipientMapper.update(any(), any())).thenReturn(1);

        var data = newService(typeServiceReturning(null)).detailAndMarkRead(1L, "u1", "5");
        assertThat(data).containsEntry("read_status", "READ").containsEntry("title", "T")
                .containsEntry("level", "URGENT").containsEntry("link_url", "https://x");
        verify(recipientMapper).update(any(), any());
    }

    @Test
    void detail_already_read_skips_update() {
        NfyaMessage m = new NfyaMessage();
        m.setId(5L);
        m.setTenantId(1L);
        m.setStatus("SENT");
        when(messageMapper.selectById(5L)).thenReturn(m);
        NfyaMessageRecipient r = new NfyaMessageRecipient();
        r.setReadStatus("READ");
        when(recipientMapper.selectOne(any())).thenReturn(r); // detailAndMarkRead 直调单参 selectOne
        when(recipientMapper.selectOne(any(), anyBoolean())).thenReturn(r);

        var data = newService(typeServiceReturning(null)).detailAndMarkRead(1L, "u1", "5");
        assertThat(data.get("read_status")).isEqualTo("READ");
        verify(recipientMapper, never()).update(any(), any());
    }

    @Test
    void detail_cancelled_or_foreign_or_missing_is_10400() {
        MessageService service = newService(typeServiceReturning(null));
        NfyaMessage cancelled = new NfyaMessage();
        cancelled.setId(5L);
        cancelled.setTenantId(1L);
        cancelled.setStatus("CANCELLED"); // 撤回消息用户侧不可见
        when(messageMapper.selectById(5L)).thenReturn(cancelled);
        when(recipientMapper.selectOne(any())).thenReturn(new NfyaMessageRecipient());
        when(recipientMapper.selectOne(any(), anyBoolean())).thenReturn(new NfyaMessageRecipient());
        assertThatThrownBy(() -> service.detailAndMarkRead(1L, "u1", "5"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));

        when(messageMapper.selectById(5L)).thenReturn(null);
        assertThatThrownBy(() -> service.detailAndMarkRead(1L, "u1", "5"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));

        NfyaMessage ok = new NfyaMessage();
        ok.setId(5L);
        ok.setTenantId(1L);
        ok.setStatus("SENT");
        when(messageMapper.selectById(5L)).thenReturn(ok);
        when(recipientMapper.selectOne(any())).thenReturn(null); // 非本人回执（单参直调）
        when(recipientMapper.selectOne(any(), anyBoolean())).thenReturn(null);
        assertThatThrownBy(() -> service.detailAndMarkRead(1L, "u1", "5"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));

        assertThatThrownBy(() -> service.detailAndMarkRead(1L, "u1", "abc"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
    }

    // ---- recent / list ----

    private static NfyaMessageRecipient recipient(long messageId, String readStatus, OffsetDateTime createdAt) {
        NfyaMessageRecipient r = new NfyaMessageRecipient();
        r.setId(messageId * 10);
        r.setMessageId(messageId);
        r.setUserid("u1");
        r.setReadStatus(readStatus);
        r.setCreatedAt(createdAt);
        return r;
    }

    private static NfyaMessage message(long id, String title) {
        NfyaMessage m = new NfyaMessage();
        m.setId(id);
        m.setTitle(title);
        m.setTypeCode("OTC");
        m.setLevel("NORMAL");
        return m;
    }

    @Test
    void recent_clamps_limit_and_maps_items() {
        OffsetDateTime t = OffsetDateTime.parse("2026-06-01T00:00Z");
        when(recipientMapper.selectList(any())).thenReturn(List.of(
                recipient(1L, "READ", t), recipient(2L, "UNREAD", t.plusMinutes(1))));
        when(messageMapper.selectBatchIds(any())).thenReturn(List.of(message(1L, "一"), message(2L, "二")));

        Map<String, Object> data = newService(typeServiceReturning(null)).recent(1L, "u1", null);
        assertThat(data.get("has_more")).isEqualTo(false);
        assertThat(data.get("next_cursor")).isNull(); // 未翻页不返回 cursor
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("list");
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("message_id", "1").containsEntry("title", "一")
                .containsEntry("read_status", "READ");
        assertThat(items.get(1)).containsEntry("title", "二");
    }

    @Test
    void list_has_more_truncates_page_and_encodes_cursor() {
        OffsetDateTime t = OffsetDateTime.parse("2026-06-01T00:00Z");
        when(recipientMapper.selectList(any())).thenReturn(List.of(
                recipient(1L, "READ", t), recipient(2L, "READ", t), recipient(3L, "READ", t)));
        when(messageMapper.selectBatchIds(any())).thenReturn(List.of(
                message(1L, "一"), message(2L, "二")));

        Map<String, Object> data = newService(typeServiceReturning(null))
                .list(1L, "u1", null, null, null, null, 2);
        assertThat(data.get("has_more")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("list");
        assertThat(items).hasSize(2); // 截断到 limit
        String cursor = (String) data.get("next_cursor");
        assertThat(cursor).isNotBlank();
        String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        assertThat(raw).isEqualTo(t.toInstant().toEpochMilli() + "_20"); // 末页行 id=2*10
    }

    @Test
    void list_invalid_cursor_throws_10102() {
        assertThatThrownBy(() -> newService(typeServiceReturning(null))
                .list(1L, "u1", null, null, null, "@@not-base64@@", 10))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10102);
                    assertThat(e.getMessage()).contains("cursor");
                });
    }

    @Test
    void list_with_all_filters_and_cursor_branches_executes() {
        when(recipientMapper.selectList(any())).thenReturn(List.of());
        var data = newService(typeServiceReturning(null))
                .list(1L, "u1", "OTC", "URGENT", "UNREAD", "  ", 0); // 空白 cursor 直通；limit 0 → 20
        assertThat(data.get("list")).isEqualTo(List.of());
        assertThat(data.get("has_more")).isEqualTo(false);
    }

    // ---- markRead ----

    @Test
    void mark_read_rejects_double_input_and_missing_input() {
        MessageService service = newService(typeServiceReturning(null));
        assertThatThrownBy(() -> service.markRead(1L, "u1", List.of("1"), true))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10101));
        assertThatThrownBy(() -> service.markRead(1L, "u1", null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10101));
        assertThatThrownBy(() -> service.markRead(1L, "u1", List.of(), false))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10101));
    }

    @Test
    void mark_read_rejects_over_100_and_bad_ids() {
        MessageService service = newService(typeServiceReturning(null));
        List<String> tooMany = java.util.stream.IntStream.range(0, 101)
                .mapToObj(String::valueOf).collect(Collectors.toList());
        assertThatThrownBy(() -> service.markRead(1L, "u1", tooMany, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10102));
        assertThatThrownBy(() -> service.markRead(1L, "u1", List.of("1", "x"), null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10102);
                    assertThat(e.getMessage()).contains("非法 id");
                });
    }

    @Test
    void mark_read_ids_updates_by_message_ids() {
        when(recipientMapper.update(any(), any())).thenReturn(5);
        var data = newService(typeServiceReturning(null)).markRead(1L, "u1", List.of("1", "2"), null);
        assertThat(data).isEqualTo(Map.of("read_count", 5));
        verify(recipientMapper).update(any(), any());
    }

    @Test
    void mark_read_all_batches_until_short_batch() {
        // 满批 500 → 续扫；短批 200 → 更新后收敛（<500 停止）；update 各命中 3
        List<NfyaMessageRecipient> fullBatch = java.util.stream.IntStream.range(0, 500)
                .mapToObj(i -> recipient(i + 1L, "UNREAD", null)).collect(Collectors.toList());
        List<NfyaMessageRecipient> shortBatch = java.util.stream.IntStream.range(0, 200)
                .mapToObj(i -> recipient(i + 1000L, "UNREAD", null)).collect(Collectors.toList());
        when(recipientMapper.selectList(any())).thenReturn(fullBatch, shortBatch, List.of());
        when(recipientMapper.update(any(), any())).thenReturn(3);

        var data = newService(typeServiceReturning(null)).markRead(1L, "u1", null, true);
        assertThat(data).isEqualTo(Map.of("read_count", 6));
        verify(recipientMapper, org.mockito.Mockito.times(2)).update(any(), any());
        verify(recipientMapper, org.mockito.Mockito.times(2)).selectList(any()); // 短批后即收敛，无第三次扫描
    }

    @Test
    void mark_read_all_with_zero_unread_short_circuits() {
        when(recipientMapper.selectList(any())).thenReturn(List.of());
        var data = newService(typeServiceReturning(null)).markRead(1L, "u1", null, true);
        assertThat(data).isEqualTo(Map.of("read_count", 0));
        verify(recipientMapper, never()).update(any(), any());
    }
}
