package fun.commons.notification4j.service;

import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.entity.NfyaDelivery;
import fun.commons.notification4j.mapper.NfyaDeliveryMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 25 步 service 层 mock 单测：DeliveryAdminService（投递记录查询 + 人工重投，任务范围外的
 * 机会性补齐——同属 service 包覆盖率口径）。DLV-001 过滤分支（bizNo/userid/channelType/status/
 * created_before，Offset 分页）/DLV-002 仅 DEAD 可重投（10402）、跨租户 10400、重投置 PENDING。
 */
// VECTOR: TAG=step25-unit
class DeliveryAdminServiceTest {

    private final NfyaDeliveryMapper deliveryMapper = mock(NfyaDeliveryMapper.class);
    private final DeliveryAdminService service = new DeliveryAdminService(deliveryMapper);

    private static NfyaDelivery delivery(long id, String status) {
        NfyaDelivery d = new NfyaDelivery();
        d.setId(id);
        d.setTenantId(1L);
        d.setSourceType("MESSAGE");
        d.setSourceId(9L);
        d.setUserid("u1");
        d.setChannelId(3L);
        d.setChannelType("EMAIL");
        d.setTarget("j***@example.com"); // 快照已脱敏
        d.setTitle("标题");
        d.setStatus(status);
        d.setRetryCount(3);
        d.setErrorMessage("boom");
        d.setSentAt(OffsetDateTime.parse("2026-06-01T01:00Z"));
        return d;
    }

    @Test
    void list_applies_filters_and_maps_rows() {
        when(deliveryMapper.selectList(any())).thenReturn(List.of(delivery(1L, "DEAD")));
        when(deliveryMapper.selectCount(any())).thenReturn(12L);
        var data = service.list(1L, "BIZ", "u1", "EMAIL", "DEAD", null, 1_800_000_000_000L, 0, 10);
        assertThat(data.get("total")).isEqualTo(12L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("list");
        assertThat(items.get(0)).containsEntry("delivery_id", "1").containsEntry("source_id", "9")
                .containsEntry("channel_id", "3").containsEntry("target", "j***@example.com")
                .containsEntry("retry_count", 3).containsEntry("error_message", "boom")
                .containsEntry("sent_at", OffsetDateTime.parse("2026-06-01T01:00Z").toInstant().toEpochMilli());
    }

    @Test
    void list_with_no_filters_maps_empty() {
        when(deliveryMapper.selectList(any())).thenReturn(List.of());
        when(deliveryMapper.selectCount(any())).thenReturn(0L);
        var data = service.list(1L, null, null, null, null, null, null, null, null);
        assertThat(data.get("list")).isEqualTo(List.of());
        assertThat(data.get("total")).isEqualTo(0L);
    }

    @Test
    void retry_rejects_non_numeric_missing_and_cross_tenant_with_10400() {
        assertThatThrownBy(() -> service.retry(1L, "abc"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
        when(deliveryMapper.selectById(1L)).thenReturn(null);
        assertThatThrownBy(() -> service.retry(1L, "1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
        NfyaDelivery foreign = delivery(1L, "DEAD");
        foreign.setTenantId(9L);
        when(deliveryMapper.selectById(1L)).thenReturn(foreign);
        assertThatThrownBy(() -> service.retry(1L, "1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
    }

    @Test
    void retry_non_dead_status_throws_10402() {
        when(deliveryMapper.selectById(1L)).thenReturn(delivery(1L, "SUCCESS"));
        assertThatThrownBy(() -> service.retry(1L, "1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10402));
        verify(deliveryMapper, org.mockito.Mockito.never()).updateById(any(NfyaDelivery.class));
    }

    @Test
    void retry_dead_delivery_resets_to_pending_immediately() {
        when(deliveryMapper.selectById(1L)).thenReturn(delivery(1L, "DEAD"));
        when(deliveryMapper.updateById(any(NfyaDelivery.class))).thenReturn(1);
        var data = service.retry(1L, "1");
        ArgumentCaptor<NfyaDelivery> cap = ArgumentCaptor.forClass(NfyaDelivery.class);
        verify(deliveryMapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(cap.getValue().getNextRetryAt()).isNotNull(); // 立即可扫
        assertThat(data).isEqualTo(Map.of("delivery_id", "1", "status", "PENDING"));
    }
}
