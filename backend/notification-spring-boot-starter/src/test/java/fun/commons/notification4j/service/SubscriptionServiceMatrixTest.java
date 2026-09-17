package fun.commons.notification4j.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.dto.PutSubscriptionsRequest;
import fun.commons.notification4j.entity.NfyaChannel;
import fun.commons.notification4j.entity.NfyaMessageType;
import fun.commons.notification4j.entity.NfyaSubscription;
import fun.commons.notification4j.mapper.NfyaChannelMapper;
import fun.commons.notification4j.mapper.NfyaMessageTypeMapper;
import fun.commons.notification4j.mapper.NfyaSubscriptionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 25 步 service 层 mock 单测：SubscriptionService（订阅矩阵 GET 三段 + PUT 全量替换）。
 * 矩阵全量替换语义：保存行（insert）vs 更新行（channel_ids/quiet json 变更才 update）；
 * quiet 缺省重置 "{}"；强制集类型语义校验（mandatory=1 → default_channels 每类型须保 ≥1 实例，
 * 无实例豁免）；INAPP 哨兵恒首位；渠道删除级联剔除（行保底回落 ["INAPP"]）。
 * MessageTypeService 走真实实例（内部 mapper mock），其余 mapper 全 mock。
 */
// VECTOR: TAG=step25-unit
class SubscriptionServiceMatrixTest {

    private final NfyaSubscriptionMapper subscriptionMapper = mock(NfyaSubscriptionMapper.class);
    private final NfyaChannelMapper channelMapper = mock(NfyaChannelMapper.class);
    private final NfyaMessageTypeMapper typeMapper = mock(NfyaMessageTypeMapper.class);

    private SubscriptionService newService() {
        MessageTypeService typeService = new MessageTypeService(new ObjectMapper());
        ServiceMockSupport.injectBaseMapper(typeService, typeMapper);
        // SubscriptionService.get 走 messageTypeService.lambdaQuery() → 预置链式元数据缓存
        ServiceMockSupport.primeServiceImplMetadata(typeService, NfyaMessageTypeMapper.class, NfyaMessageType.class);
        return new SubscriptionService(subscriptionMapper, channelMapper, typeService, new ObjectMapper());
    }

    private NfyaMessageType type(String code, Integer mandatory, String defaultChannels) {
        NfyaMessageType t = new NfyaMessageType();
        t.setTenantId(1L);
        t.setTypeCode(code);
        t.setName("类型" + code);
        t.setDescription("描述");
        t.setDefaultChannels(defaultChannels);
        t.setMandatory(mandatory);
        t.setStatus("ENABLED");
        return t;
    }

    private NfyaChannel enabledChannel(long id, String channelType) {
        NfyaChannel ch = new NfyaChannel();
        ch.setId(id);
        ch.setTenantId(1L);
        ch.setUserid("u1");
        ch.setScope("USER");
        ch.setChannelType(channelType);
        ch.setName("渠道" + id);
        ch.setStatus("ENABLED");
        return ch;
    }

    private NfyaSubscription row(String typeCode, String channelIds, String quiet) {
        NfyaSubscription r = new NfyaSubscription();
        r.setId(100L);
        r.setTenantId(1L);
        r.setUserid("u1");
        r.setTypeCode(typeCode);
        r.setChannelIds(channelIds);
        r.setQuietHours(quiet);
        return r;
    }

    private PutSubscriptionsRequest request(PutSubscriptionsRequest.Item... items) {
        return new PutSubscriptionsRequest(List.of(items));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listAt(Map<String, Object> data, String key) {
        return (List<Map<String, Object>>) data.get(key);
    }

    // ---- GET 三段 ----

    @Test
    void get_returns_types_available_channels_and_items() {
        when(typeMapper.selectList(any())).thenReturn(List.of(
                type("OTC", 1, "[\"EMAIL\",\"INAPP\"]")));
        when(channelMapper.selectList(any())).thenReturn(List.of(
                enabledChannel(1L, "EMAIL"), enabledChannel(2L, "DINGTALK")));
        when(subscriptionMapper.selectList(any())).thenReturn(List.of(
                row("OTC", "[\"1\",\"INAPP\"]", "{\"start\":\"22:00\",\"end\":\"08:00\"}"),
                row("PAY", "[\"INAPP\"]", "{}")));

        Map<String, Object> data = newService().get(1L, "u1");

        assertThat(listAt(data, "types")).hasSize(1);
        assertThat(listAt(data, "types").get(0)).containsEntry("type_code", "OTC")
                .containsEntry("mandatory", 1).isEqualTo(Map.of(
                        "type_code", "OTC", "name", "类型OTC", "description", "描述",
                        "mandatory", 1, "default_channels", List.of("EMAIL", "INAPP")));

        List<Map<String, Object>> available = listAt(data, "available_channels");
        assertThat(available.get(0)).containsEntry("channel_id", "INAPP"); // INAPP 哨兵恒首位
        assertThat(available.get(1)).containsEntry("channel_id", "1").containsEntry("channel_type", "EMAIL");
        assertThat(available).hasSize(3);

        List<Map<String, Object>> items = listAt(data, "items");
        assertThat(items.get(0)).containsEntry("channel_ids", List.of("1", "INAPP"))
                .containsEntry("quiet_hours", Map.of("start", "22:00", "end", "08:00")); // 启用行原样回显
        assertThat(items.get(1)).doesNotContainKey("quiet_hours"); // 未启用行不返回该字段（缺省=未启用）
    }

    @Test
    void get_with_nothing_configured_returns_sentinel_only() {
        when(typeMapper.selectList(any())).thenReturn(List.of());
        when(channelMapper.selectList(any())).thenReturn(List.of());
        when(subscriptionMapper.selectList(any())).thenReturn(List.of());
        Map<String, Object> data = newService().get(1L, "u1");
        assertThat(listAt(data, "available_channels")).isEqualTo(List.of(Map.of("channel_id", "INAPP")));
        assertThat(listAt(data, "items")).isEmpty();
        assertThat(listAt(data, "types")).isEmpty();
    }

    // ---- PUT 全量替换 ----

    @Test
    void save_new_type_inserts_row_with_inapp_first() {
        when(typeMapper.selectOne(any(), anyBoolean())).thenReturn(type("OTC", 0, "[\"INAPP\"]"));
        when(channelMapper.selectList(any())).thenReturn(List.of(enabledChannel(5L, "DINGTALK")));
        when(channelMapper.selectById(5L)).thenReturn(enabledChannel(5L, "DINGTALK")); // 渠道属主校验
        when(subscriptionMapper.selectList(any())).thenReturn(List.of()); // 无既有行

        var data = newService().save(1L, "u1",
                request(new PutSubscriptionsRequest.Item("OTC", List.of("5", "INAPP"),
                        new PutSubscriptionsRequest.QuietHours("22:00", "08:00"))));

        assertThat(data).isEqualTo(Map.of("saved_count", 1));
        ArgumentCaptor<NfyaSubscription> cap = ArgumentCaptor.forClass(NfyaSubscription.class);
        verify(subscriptionMapper).insert(cap.capture());
        assertThat(cap.getValue().getChannelIds()).isEqualTo("[\"INAPP\",\"5\"]"); // INAPP 恒首位，重复 INAPP 去重
        assertThat(cap.getValue().getQuietHours()).isEqualTo("{\"start\":\"22:00\",\"end\":\"08:00\"}");
        assertThat(cap.getValue().getExt()).isEqualTo("{}");
        verify(subscriptionMapper, never()).updateById(any(NfyaSubscription.class));
    }

    @Test
    void save_changed_row_updates_instead_of_insert() {
        when(typeMapper.selectOne(any(), anyBoolean())).thenReturn(type("OTC", 0, "[\"INAPP\"]"));
        when(channelMapper.selectList(any())).thenReturn(List.of(enabledChannel(5L, "DINGTALK")));
        when(channelMapper.selectById(5L)).thenReturn(enabledChannel(5L, "DINGTALK"));
        when(subscriptionMapper.selectList(any())).thenReturn(List.of(
                row("OTC", "[\"INAPP\",\"5\"]", "{}"))); // 既有行 channel_ids 不同

        newService().save(1L, "u1",
                request(new PutSubscriptionsRequest.Item("OTC", List.of("5"),
                        new PutSubscriptionsRequest.QuietHours("22:00", "08:00"))));

        ArgumentCaptor<NfyaSubscription> cap = ArgumentCaptor.forClass(NfyaSubscription.class);
        verify(subscriptionMapper).updateById(cap.capture());
        assertThat(cap.getValue().getId()).isEqualTo(100L);
        assertThat(cap.getValue().getChannelIds()).isEqualTo("[\"INAPP\",\"5\"]");
        assertThat(cap.getValue().getQuietHours()).isEqualTo("{\"start\":\"22:00\",\"end\":\"08:00\"}");
        verify(subscriptionMapper, never()).insert(any(NfyaSubscription.class));
    }

    @Test
    void save_identical_row_skips_write() {
        when(typeMapper.selectOne(any(), anyBoolean())).thenReturn(type("OTC", 0, "[\"INAPP\"]"));
        when(channelMapper.selectList(any())).thenReturn(List.of());
        when(subscriptionMapper.selectList(any())).thenReturn(List.of(
                row("OTC", "[\"INAPP\"]", "{}"))); // 与归一化结果完全一致

        newService().save(1L, "u1",
                request(new PutSubscriptionsRequest.Item("OTC", List.of("INAPP"), null))); // 与既有行完全一致
        verify(subscriptionMapper, never()).insert(any(NfyaSubscription.class));
        verify(subscriptionMapper, never()).updateById(any(NfyaSubscription.class));
    }

    @Test
    void save_missing_quiet_resets_existing_window() {
        when(typeMapper.selectOne(any(), anyBoolean())).thenReturn(type("OTC", 0, "[\"INAPP\"]"));
        when(channelMapper.selectList(any())).thenReturn(List.of());
        when(subscriptionMapper.selectList(any())).thenReturn(List.of(
                row("OTC", "[\"INAPP\"]", "{\"start\":\"22:00\",\"end\":\"08:00\"}")));

        newService().save(1L, "u1",
                request(new PutSubscriptionsRequest.Item("OTC", List.of("INAPP"), null))); // quiet 缺省 → 重置
        ArgumentCaptor<NfyaSubscription> cap = ArgumentCaptor.forClass(NfyaSubscription.class);
        verify(subscriptionMapper).updateById(cap.capture());
        assertThat(cap.getValue().getQuietHours()).isEqualTo("{}");
    }

    @Test
    void save_deletes_unsubmitted_types_and_keeps_submitted() {
        when(typeMapper.selectOne(any(), anyBoolean())).thenReturn(type("OTC", 0, "[\"INAPP\"]"));
        when(channelMapper.selectList(any())).thenReturn(List.of());
        NfyaSubscription keep = row("OTC", "[\"INAPP\"]", "{}");
        NfyaSubscription stale1 = row("PAY", "[\"INAPP\"]", "{}");
        stale1.setId(101L);
        NfyaSubscription stale2 = row("RPT", "[\"INAPP\"]", "{}");
        stale2.setId(102L);
        when(subscriptionMapper.selectList(any())).thenReturn(List.of(keep, stale1, stale2));

        newService().save(1L, "u1",
                request(new PutSubscriptionsRequest.Item("OTC", List.of("INAPP"), null)));
        verify(subscriptionMapper).deleteById(101L); // 仅删未提交类型
        verify(subscriptionMapper).deleteById(102L);
        verify(subscriptionMapper, never()).deleteById(100L); // 已提交类型保留
        verify(subscriptionMapper, never()).insert(any(NfyaSubscription.class)); // 行未变不重写
    }

    @Test
    void save_empty_items_deletes_everything() {
        when(channelMapper.selectList(any())).thenReturn(List.of());
        when(subscriptionMapper.selectList(any())).thenReturn(List.of(
                row("OTC", "[\"INAPP\"]", "{}")));
        var data = newService().save(1L, "u1", request());
        assertThat(data).isEqualTo(Map.of("saved_count", 0));
        verify(subscriptionMapper).deleteById(100L);
    }

    // ---- PUT 参数校验 ----

    @Test
    void save_rejects_blank_type_code_duplicate_type_and_empty_channels() {
        when(typeMapper.selectOne(any(), anyBoolean())).thenReturn(type("OTC", 0, "[\"INAPP\"]"));
        SubscriptionService service = newService();
        assertThatThrownBy(() -> service.save(1L, "u1",
                request(new PutSubscriptionsRequest.Item("  ", List.of("INAPP"), null))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10101);
                    assertThat(e.getMessage()).contains("type_code");
                });
        assertThatThrownBy(() -> service.save(1L, "u1", request(
                new PutSubscriptionsRequest.Item("OTC", List.of("INAPP"), null),
                new PutSubscriptionsRequest.Item("OTC", List.of("INAPP"), null))))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.getCode()).isEqualTo(10100));
        assertThatThrownBy(() -> service.save(1L, "u1",
                request(new PutSubscriptionsRequest.Item("OTC", List.of(), null))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10101);
                    assertThat(e.getMessage()).contains("至少 1 个");
                });
    }

    @Test
    void save_unknown_or_disabled_type_throws_10601() {
        when(typeMapper.selectOne(any(), anyBoolean())).thenReturn(null);
        assertThatThrownBy(() -> newService().save(1L, "u1",
                request(new PutSubscriptionsRequest.Item("GHOST", List.of("INAPP"), null))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10601));
    }

    @Test
    void save_foreign_or_missing_channel_throws_10400() {
        when(typeMapper.selectOne(any(), anyBoolean())).thenReturn(type("OTC", 0, "[\"INAPP\"]"));
        when(channelMapper.selectList(any())).thenReturn(List.of());
        when(channelMapper.selectById(5L)).thenReturn(null); // id 不存在
        assertThatThrownBy(() -> newService().save(1L, "u1",
                request(new PutSubscriptionsRequest.Item("OTC", List.of("5"), null))))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.getMessage()).isEqualTo("渠道不存在或不可用"));
        assertThatThrownBy(() -> newService().save(1L, "u1",
                request(new PutSubscriptionsRequest.Item("OTC", List.of("abc"), null))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
    }

    // ---- 强制集（类型语义 → 实例豁免/10606）----

    @Test
    void save_mandatory_type_requires_last_instance_10606() {
        when(typeMapper.selectOne(any(), anyBoolean())).thenReturn(type("PAY", 1, "[\"EMAIL\",\"INAPP\"]"));
        when(channelMapper.selectList(any())).thenReturn(List.of(enabledChannel(5L, "EMAIL"))); // 有 EMAIL 实例
        assertThatThrownBy(() -> newService().save(1L, "u1",
                request(new PutSubscriptionsRequest.Item("PAY", List.of("INAPP"), null)))) // 只留 INAPP
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10606);
                    assertThat(e.getMessage()).contains("不可关闭");
                });
        verify(subscriptionMapper, never()).insert(any(NfyaSubscription.class));
    }

    @Test
    void save_mandatory_type_exempt_when_user_has_no_instance() {
        when(typeMapper.selectOne(any(), anyBoolean())).thenReturn(type("PAY", 1, "[\"EMAIL\",\"INAPP\"]"));
        when(channelMapper.selectList(any())).thenReturn(List.of()); // 无任何注册渠道 → 豁免（INAPP 兜底）
        when(subscriptionMapper.selectList(any())).thenReturn(List.of());
        var data = newService().save(1L, "u1",
                request(new PutSubscriptionsRequest.Item("PAY", List.of("INAPP"), null)));
        assertThat(data).isEqualTo(Map.of("saved_count", 1));
        ArgumentCaptor<NfyaSubscription> cap = ArgumentCaptor.forClass(NfyaSubscription.class);
        verify(subscriptionMapper).insert(cap.capture());
        assertThat(cap.getValue().getChannelIds()).isEqualTo("[\"INAPP\"]");
    }

    @Test
    void save_mandatory_type_passes_when_instance_kept() {
        when(typeMapper.selectOne(any(), anyBoolean())).thenReturn(type("PAY", 1, "[\"EMAIL\",\"INAPP\"]"));
        when(channelMapper.selectList(any())).thenReturn(List.of(enabledChannel(5L, "EMAIL")));
        when(channelMapper.selectById(5L)).thenReturn(enabledChannel(5L, "EMAIL"));
        when(subscriptionMapper.selectList(any())).thenReturn(List.of());
        newService().save(1L, "u1",
                request(new PutSubscriptionsRequest.Item("PAY", List.of("5"), null)));
        verify(subscriptionMapper).insert(any(NfyaSubscription.class));
    }

    @Test
    void save_non_mandatory_type_never_enforces() {
        when(typeMapper.selectOne(any(), anyBoolean())).thenReturn(type("OTC", 0, "[\"EMAIL\"]"));
        when(channelMapper.selectList(any())).thenReturn(List.of(enabledChannel(5L, "EMAIL")));
        when(subscriptionMapper.selectList(any())).thenReturn(List.of());
        newService().save(1L, "u1",
                request(new PutSubscriptionsRequest.Item("OTC", List.of("INAPP"), null)));
        verify(subscriptionMapper).insert(any(NfyaSubscription.class));
    }

    // ---- removeChannel（删除渠道级联剔除）----

    @Test
    void remove_channel_prunes_id_and_falls_back_to_inapp() {
        NfyaSubscription multi = row("OTC", "[\"INAPP\",\"5\",\"7\"]", "{}");
        NfyaSubscription only = row("PAY", "[\"7\"]", "{}");
        NfyaSubscription untouched = row("RPT", "[\"INAPP\"]", "{}");
        when(subscriptionMapper.selectList(any())).thenReturn(List.of(multi, only, untouched));

        newService().removeChannel(1L, "u1", "7");

        ArgumentCaptor<NfyaSubscription> cap = ArgumentCaptor.forClass(NfyaSubscription.class);
        verify(subscriptionMapper, org.mockito.Mockito.times(2)).updateById(cap.capture());
        assertThat(cap.getAllValues().get(0).getChannelIds()).isEqualTo("[\"INAPP\",\"5\"]");
        assertThat(cap.getAllValues().get(1).getChannelIds()).isEqualTo("[\"INAPP\"]"); // 剔空后保底回落
        assertThat(untouched.getChannelIds()).isEqualTo("[\"INAPP\"]"); // 未含失效 id 不动
    }
}
