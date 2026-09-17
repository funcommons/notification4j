package fun.commons.notification4j.service;

import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.entity.NfyaTemplate;
import fun.commons.notification4j.mapper.NfyaTemplateMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 25 步 service 层 mock 单测：TemplateService CRUD（渲染逻辑已在第 24 步覆盖）。
 * create（动态 schema 必填手工校验 10100 携字段名/唯一闸 10401/channel_content 缺省 "{}"）/
 * list（channel_content jsonb 解析 + 脏数据 fail-open {}）/detail（防探测 10400）/
 * patch（部分更新 + status 枚举 10100）/delete（逻辑删；删后可重建口径在唯一索引侧，此处验证删除行为）。
 */
// VECTOR: TAG=step25-unit
class TemplateServiceCrudTest {

    private final NfyaTemplateMapper templateMapper = mock(NfyaTemplateMapper.class);

    private TemplateService newService() {
        return ServiceMockSupport.injectBaseMapper(
                new TemplateService(new com.fasterxml.jackson.databind.ObjectMapper()), templateMapper);
    }

    private static Map<String, Object> req() {
        Map<String, Object> m = new HashMap<>();
        m.put("template_code", "TPL_CODE");
        m.put("name", "模板");
        m.put("type_code", "OTC");
        m.put("title_tpl", "Hi {{name}}");
        m.put("content_tpl", "body {{name}}");
        return m;
    }

    private static NfyaTemplate template(long id, long tenantId, String channelContent) {
        NfyaTemplate t = new NfyaTemplate();
        t.setId(id);
        t.setTenantId(tenantId);
        t.setTemplateCode("TPL_CODE");
        t.setName("模板");
        t.setTypeCode("OTC");
        t.setTitleTpl("t");
        t.setContentTpl("c");
        t.setChannelContent(channelContent);
        t.setStatus("ENABLED");
        return t;
    }

    // ---- create ----

    @Test
    void create_missing_required_field_names_the_field() {
        TemplateService service = newService();
        for (String field : List.of("template_code", "name", "type_code", "title_tpl", "content_tpl")) {
            Map<String, Object> body = req();
            body.put(field, "  ");
            assertThatThrownBy(() -> service.create(1L, body))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(10100);
                        assertThat(e.getMessage()).contains(field);
                    });
        }
        verify(templateMapper, never()).insert(any(NfyaTemplate.class));
    }

    @Test
    void create_saves_enabled_template_with_default_channel_content() {
        Map<String, Object> body = req();
        body.put("channel_content", Map.of("DINGTALK", Map.of("title_tpl", "DT")));
        var data = newService().create(1L, body);
        ArgumentCaptor<NfyaTemplate> cap = ArgumentCaptor.forClass(NfyaTemplate.class);
        verify(templateMapper).insert(cap.capture());
        NfyaTemplate t = cap.getValue();
        assertThat(t.getTenantId()).isEqualTo(1L);
        assertThat(t.getStatus()).isEqualTo("ENABLED");
        assertThat(t.getExt()).isEqualTo("{}");
        assertThat(t.getChannelContent()).contains("DINGTALK"); // Jackson 序列化非手拼
        assertThat(data).isEqualTo(Map.of("template_id", String.valueOf(t.getId())));
    }

    @Test
    void create_duplicate_code_throws_10401() {
        when(templateMapper.insert(any(NfyaTemplate.class))).thenThrow(new DuplicateKeyException("uk"));
        assertThatThrownBy(() -> newService().create(1L, req()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10401));
    }

    // ---- list / detail ----

    @Test
    void list_maps_vos_and_parses_dirty_channel_content_fail_open() {
        when(templateMapper.selectList(any())).thenReturn(List.of(
                template(1L, 1L, "{\"DINGTALK\":{}}"),
                template(2L, 1L, "not-json{"))); // 脏 jsonb → {} 回显
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) newService().list(1L).get("list");
        assertThat(list).hasSize(2);
        assertThat(list.get(0)).containsEntry("template_id", "1")
                .containsEntry("channel_content", Map.of("DINGTALK", Map.of()))
                .containsEntry("status", "ENABLED").containsEntry("created_at", null);
        assertThat(list.get(1).get("channel_content")).isEqualTo(Map.of());
    }

    @Test
    void detail_missing_or_cross_tenant_throws_10400() {
        when(templateMapper.selectById(1L)).thenReturn(null);
        assertThatThrownBy(() -> newService().detail(1L, "1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
        when(templateMapper.selectById(1L)).thenReturn(template(1L, 9L, "{}"));
        assertThatThrownBy(() -> newService().detail(1L, "1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
        assertThatThrownBy(() -> newService().detail(1L, "abc"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
    }

    // ---- patch ----

    @Test
    void patch_applies_partial_fields_and_status() {
        when(templateMapper.selectById(1L)).thenReturn(template(1L, 1L, "{}"));
        when(templateMapper.updateById(any(NfyaTemplate.class))).thenReturn(1);
        Map<String, Object> body = new HashMap<>();
        body.put("name", "新名");
        body.put("channel_content", Map.of("FEISHU", Map.of("content_tpl", "F")));
        body.put("status", "DISABLED");
        var data = newService().patch(1L, "1", body);
        ArgumentCaptor<NfyaTemplate> cap = ArgumentCaptor.forClass(NfyaTemplate.class);
        verify(templateMapper).updateById(cap.capture());
        assertThat(cap.getValue().getName()).isEqualTo("新名");
        assertThat(cap.getValue().getStatus()).isEqualTo("DISABLED");
        assertThat(cap.getValue().getChannelContent()).contains("FEISHU");
        assertThat(data).isEqualTo(Map.of("template_id", "1"));
    }

    @Test
    void patch_rejects_illegal_status() {
        when(templateMapper.selectById(1L)).thenReturn(template(1L, 1L, "{}"));
        assertThatThrownBy(() -> newService().patch(1L, "1", Map.of("status", "PAUSED")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10100);
                    assertThat(e.getMessage()).contains("ENABLED/DISABLED");
                });
        verify(templateMapper, never()).updateById(any(NfyaTemplate.class));
    }

    // ---- delete（逻辑删）----

    @Test
    void delete_removes_owned_template() {
        when(templateMapper.selectById(1L)).thenReturn(template(1L, 1L, "{}"));
        when(templateMapper.deleteById(1L)).thenReturn(1);
        assertThat(newService().delete(1L, "1")).isEqualTo(Map.of("template_id", "1"));
        verify(templateMapper).deleteById(1L);
    }

    @Test
    void delete_requires_ownership() {
        when(templateMapper.selectById(1L)).thenReturn(null);
        assertThatThrownBy(() -> newService().delete(1L, "1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
        verify(templateMapper, never()).deleteById(1L);
    }
}
