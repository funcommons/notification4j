package fun.commons.notification4j.service;

import fun.commons.framework4j.id.util.IdObfuscator;
import fun.commons.notification4j.mapper.NfyaChannelMapper;
import fun.commons.notification4j.mapper.NfyaDeliveryMapper;
import fun.commons.notification4j.mapper.NfyaMessageMapper;
import fun.commons.notification4j.mapper.NfyaMessageRecipientMapper;
import fun.commons.notification4j.mapper.NfyaTenantMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 25 步 service 层 mock 单测：StatsService（租户/平台概览）。
 * mock 五 mapper 的 selectCount/selectMaps；万分比定标换算（7500=3/4、8000=4/5）、
 * 空数据日取 0（无样本 ≠ 全成功）、dead_tenants 的 IdMaskHelper 转换
 * （数字 tenant_id → open_id、非数字原样、null 保持 null）。
 * 时间窗（today/7d）由 mapper 参数固化，mock 不触真 SQL → 不依赖时钟推进。
 */
// VECTOR: TAG=step25-unit
class StatsServiceOverviewTest {

    private final NfyaMessageMapper messageMapper = mock(NfyaMessageMapper.class);
    private final NfyaMessageRecipientMapper recipientMapper = mock(NfyaMessageRecipientMapper.class);
    private final NfyaDeliveryMapper deliveryMapper = mock(NfyaDeliveryMapper.class);
    private final NfyaChannelMapper channelMapper = mock(NfyaChannelMapper.class);
    private final NfyaTenantMapper tenantMapper = mock(NfyaTenantMapper.class);

    private StatsService newService() {
        return new StatsService(messageMapper, recipientMapper, deliveryMapper, channelMapper, tenantMapper);
    }

    @Test
    void tenant_overview_computes_success_and_read_rates() {
        when(messageMapper.selectCount(any())).thenReturn(5L); // today_sent
        when(deliveryMapper.selectCount(any())).thenReturn(3L, 1L); // ok(=today_delivered 复用), failed
        when(channelMapper.selectCount(any())).thenReturn(2L);
        when(recipientMapper.selectCount(any())).thenReturn(10L, 5L); // sent7d, read7d

        Map<String, Object> data = newService().tenantOverview(1L);
        assertThat(data).containsEntry("today_sent", 5L).containsEntry("today_delivered", 3L)
                .containsEntry("deliver_success_rate", 7500L) // 3/(3+1) 万分比
                .containsEntry("read_rate_7d", 5000L) // 5/10
                .containsEntry("channel_count", 2L);
        verify(deliveryMapper, times(2)).selectCount(any()); // P2 修复后 today_delivered 复用 ok，不再重复查询
    }

    @Test
    void tenant_overview_empty_day_yields_zero_rates() {
        when(deliveryMapper.selectCount(any())).thenReturn(0L, 0L);
        when(recipientMapper.selectCount(any())).thenReturn(0L, 0L);
        when(messageMapper.selectCount(any())).thenReturn(0L);
        when(channelMapper.selectCount(any())).thenReturn(0L);

        Map<String, Object> data = newService().tenantOverview(1L);
        assertThat(data).containsEntry("deliver_success_rate", 0L).containsEntry("read_rate_7d", 0L);
    }

    @Test
    void tenant_overview_all_failed_is_zero_rate_not_full_success() {
        when(deliveryMapper.selectCount(any())).thenReturn(0L, 6L); // ok=0, failed=6
        when(recipientMapper.selectCount(any())).thenReturn(4L, 0L);
        when(messageMapper.selectCount(any())).thenReturn(9L);
        when(channelMapper.selectCount(any())).thenReturn(0L);

        Map<String, Object> data = newService().tenantOverview(1L);
        assertThat(data.get("deliver_success_rate")).isEqualTo(0L); // 无成功样本 ≠ 全成功
        assertThat(data.get("read_rate_7d")).isEqualTo(0L);
    }

    @Test
    void platform_overview_maps_dead_tenants_with_open_id_conversion() {
        when(tenantMapper.selectCount(any())).thenReturn(3L, 2L); // total, active
        when(messageMapper.selectCount(any())).thenReturn(7L);
        when(deliveryMapper.selectCount(any())).thenReturn(4L, 4L, 1L, 2L); // deliveries, ok, failed, dead
        Map<String, Object> nullTenantRow = new java.util.HashMap<>();
        nullTenantRow.put("tenant_id", null);
        nullTenantRow.put("cnt", 0L);
        when(deliveryMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("tenant_id", 5L, "cnt", 3L),
                Map.of("tenant_id", "x", "cnt", 1L), // 非数字 → 原样
                nullTenantRow));

        Map<String, Object> data = newService().platformOverview();
        assertThat(data).containsEntry("tenant_count", 3L).containsEntry("active_tenants", 2L)
                .containsEntry("today_messages", 7L).containsEntry("today_deliveries", 4L)
                .containsEntry("deliver_success_rate", 8000L) // 4/(4+1)
                .containsEntry("dead_count", 2L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deadTenants = (List<Map<String, Object>>) data.get("dead_tenants");
        assertThat(deadTenants).hasSize(3);
        assertThat(deadTenants.get(0)).containsEntry("open_id", IdObfuscator.toOpenId(5L))
                .containsEntry("dead_count", 3L);
        assertThat(deadTenants.get(1)).containsEntry("open_id", "x");
        assertThat(deadTenants.get(2)).containsEntry("open_id", null);
    }

    @Test
    void platform_overview_empty_yields_zero_rate_and_empty_dead_list() {
        when(tenantMapper.selectCount(any())).thenReturn(0L, 0L);
        when(messageMapper.selectCount(any())).thenReturn(0L);
        when(deliveryMapper.selectCount(any())).thenReturn(0L, 0L, 0L, 0L);
        when(deliveryMapper.selectMaps(any())).thenReturn(List.of());

        Map<String, Object> data = newService().platformOverview();
        assertThat(data.get("deliver_success_rate")).isEqualTo(0L);
        assertThat(data.get("dead_tenants")).isEqualTo(List.of());
    }
}
