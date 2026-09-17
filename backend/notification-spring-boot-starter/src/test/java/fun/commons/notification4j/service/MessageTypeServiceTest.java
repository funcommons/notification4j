package fun.commons.notification4j.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.dto.PatchTypesRequest;
import fun.commons.notification4j.dto.PostTypesRequest;
import fun.commons.notification4j.entity.NfyaMessageType;
import fun.commons.notification4j.mapper.NfyaMessageTypeMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 25 步 service 层 mock 单测：MessageTypeService（租户类型 CRUD + 发送前置校验）。
 * create（缺省值/唯一闸 10401/level 枚举 10100/default_channels 白名单与空列表 10100）/
 * update（部分更新/builtin 不可停用 10402/status 枚举 10100/跨租户 10400）/requireEnabled（10601）。
 */
// VECTOR: TAG=step25-unit
class MessageTypeServiceTest {

    private final NfyaMessageTypeMapper typeMapper = mock(NfyaMessageTypeMapper.class);

    private MessageTypeService newService() {
        return ServiceMockSupport.injectBaseMapper(new MessageTypeService(new ObjectMapper()), typeMapper);
    }

    private static NfyaMessageType type(long tenantId, String code, String status, Integer builtIn) {
        NfyaMessageType t = new NfyaMessageType();
        t.setId(11L);
        t.setTenantId(tenantId);
        t.setTypeCode(code);
        t.setName("名称");
        t.setStatus(status);
        t.setBuiltIn(builtIn);
        t.setMandatory(0);
        t.setDefaultChannels("[\"INAPP\"]");
        return t;
    }

    @Test
    void create_applies_defaults_and_saves_enabled_type() {
        var data = newService().create(1L, new PostTypesRequest("OTC", "名称", null, null, null));
        ArgumentCaptor<NfyaMessageType> cap = ArgumentCaptor.forClass(NfyaMessageType.class);
        verify(typeMapper).insert(cap.capture());
        NfyaMessageType t = cap.getValue();
        assertThat(t.getDescription()).isEmpty();
        assertThat(t.getDefaultLevel()).isEqualTo("NORMAL");
        assertThat(t.getDefaultChannels()).isEqualTo("[\"INAPP\"]");
        assertThat(t.getMandatory()).isZero(); // mandatory 仅平台域可设
        assertThat(t.getBuiltIn()).isZero();
        assertThat(t.getStatus()).isEqualTo("ENABLED");
        assertThat(data.getTypeCode()).isEqualTo("OTC");
    }

    @Test
    void create_duplicate_code_throws_10401() {
        when(typeMapper.insert(any(NfyaMessageType.class))).thenThrow(new DuplicateKeyException("uk"));
        assertThatThrownBy(() -> newService().create(1L, new PostTypesRequest("DUP", "名称", null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10401));
    }

    @Test
    void create_rejects_bad_level_and_bad_channel_enums() {
        MessageTypeService service = newService();
        assertThatThrownBy(() -> service.create(1L,
                new PostTypesRequest("OTC", "名称", null, "HIGH", null)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10100);
                    assertThat(e.getMessage()).contains("NORMAL/IMPORTANT/URGENT");
                });
        assertThatThrownBy(() -> service.create(1L,
                new PostTypesRequest("OTC", "名称", null, null, List.of())))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("至少 1 个"));
        assertThatThrownBy(() -> service.create(1L,
                new PostTypesRequest("OTC", "名称", null, null, List.of("SMS"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("INAPP/DINGTALK"));
        verify(typeMapper, org.mockito.Mockito.never()).insert(any(NfyaMessageType.class));
    }

    @Test
    void list_returns_tenant_types() {
        NfyaMessageType t = type(1L, "OTC", "ENABLED", 0);
        when(typeMapper.selectList(any())).thenReturn(List.of(t));
        assertThat(newService().list(1L)).containsExactly(t);
    }

    @Test
    void update_applies_partial_fields() {
        when(typeMapper.selectById(11L)).thenReturn(type(1L, "OTC", "ENABLED", 0));
        when(typeMapper.updateById(any(NfyaMessageType.class))).thenReturn(1);
        NfyaMessageType out = newService().update(1L, 11L, new PatchTypesRequest(
                "新名", "新描述", "URGENT", List.of("EMAIL", "INAPP"), "DISABLED"));
        assertThat(out.getName()).isEqualTo("新名");
        assertThat(out.getDefaultLevel()).isEqualTo("URGENT");
        assertThat(out.getDefaultChannels()).isEqualTo("[\"EMAIL\",\"INAPP\"]");
        assertThat(out.getStatus()).isEqualTo("DISABLED");
        verify(typeMapper).updateById(out);
    }

    @Test
    void update_rejects_illegal_status_and_builtin_disable() {
        when(typeMapper.selectById(11L)).thenReturn(type(1L, "OTC", "ENABLED", 0));
        assertThatThrownBy(() -> newService().update(1L, 11L,
                new PatchTypesRequest(null, null, null, null, "PAUSED")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10100));
        when(typeMapper.selectById(11L)).thenReturn(type(1L, "OTC", "ENABLED", 1)); // 内置类型
        assertThatThrownBy(() -> newService().update(1L, 11L,
                new PatchTypesRequest(null, null, null, null, "DISABLED")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10402);
                    assertThat(e.getMessage()).contains("内置");
                });
        verify(typeMapper, org.mockito.Mockito.never()).updateById(any(NfyaMessageType.class));
    }

    @Test
    void update_missing_or_cross_tenant_throws_10400() {
        when(typeMapper.selectById(11L)).thenReturn(null);
        assertThatThrownBy(() -> newService().update(1L, 11L,
                new PatchTypesRequest("n", null, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
        when(typeMapper.selectById(11L)).thenReturn(type(9L, "OTC", "ENABLED", 0));
        assertThatThrownBy(() -> newService().update(1L, 11L,
                new PatchTypesRequest("n", null, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
    }

    @Test
    void requireEnabled_accepts_enabled_tenant_type() {
        when(typeMapper.selectOne(any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(type(1L, "OTC", "ENABLED", 0));
        assertThat(newService().requireEnabled(1L, "OTC").getTypeCode()).isEqualTo("OTC");
    }

    @Test
    void requireEnabled_rejects_missing_or_disabled_with_10601() {
        when(typeMapper.selectOne(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(null);
        assertThatThrownBy(() -> newService().requireEnabled(1L, "GHOST"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10601));
        when(typeMapper.selectOne(any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(type(1L, "OTC", "DISABLED", 0));
        assertThatThrownBy(() -> newService().requireEnabled(1L, "OTC"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10601));
    }

    @Test
    void toVoList_maps_all_fields() {
        var vo = newService().toVoList(List.of(type(1L, "OTC", "ENABLED", 1))).get(0);
        assertThat(vo).containsEntry("type_id", "11").containsEntry("type_code", "OTC")
                .containsEntry("built_in", 1).containsEntry("default_channels", "[\"INAPP\"]")
                .containsEntry("status", "ENABLED");
    }
}
