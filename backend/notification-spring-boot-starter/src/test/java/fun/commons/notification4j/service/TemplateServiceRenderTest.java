package fun.commons.notification4j.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.entity.NfyaTemplate;
import fun.commons.notification4j.mapper.NfyaTemplateMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 第 24 步纯函数层单测：TemplateService 渲染逻辑（不起 Spring、不连 DB）。
 * render 为 private → 同包反射直测纯分支；preview 走 mock NfyaTemplateMapper（反射注入
 * ServiceImpl#baseMapper，MyBatis-Plus LambdaQueryWrapper 列名解析在 SQL 生成期才触发 → mock 下不初始化）。
 * 分支：{{param}} 替换/缺参 10603 携参数名/$ 字面量 quoteReplacement/\w 外占位符原样保留/null 模板/
 * channel_content 分渠道覆盖（缺省回落全局、非 Map 值跳过、模板不存在 10400）。
 */
// VECTOR: TAG=step24-unit
class TemplateServiceRenderTest {

    private static final Method RENDER = render();

    private static Method render() {
        try {
            Method m = TemplateService.class.getDeclaredMethod("render", String.class, Map.class);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String render(String tpl, Map<String, Object> params) {
        try {
            return (String) RENDER.invoke(new TemplateService(new ObjectMapper()), tpl, params);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- render：替换与缺参 ----

    @Test
    void replaces_single_placeholder() {
        assertThat(render("Hi {{name}}", Map.of("name", "Justin"))).isEqualTo("Hi Justin");
    }

    @Test
    void replaces_repeated_and_multiple_placeholders() {
        assertThat(render("{{a}}-{{b}}-{{a}}", Map.of("a", 1, "b", "x"))).isEqualTo("1-x-1");
    }

    @Test
    void non_string_param_value_is_stringified() {
        assertThat(render("count={{count}}", Map.of("count", 42))).isEqualTo("count=42");
    }

    @Test
    void missing_param_throws_10603_with_param_name() {
        assertThatThrownBy(() -> render("Hi {{name}} {{count}}", Map.of("name", "Justin")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10603);
                    assertThat(e.getMessage()).contains("count");
                });
    }

    @Test
    void dollar_signs_in_value_are_literal_via_quote_replacement() {
        // $ 与 \ 在 appendReplacement 中是特殊字符：quoteReplacement 保证原样输出
        assertThat(render("cost {{amount}}", Map.of("amount", "$100 & ${x}\\n")))
                .isEqualTo("cost $100 & ${x}\\n");
    }

    @Test
    void non_word_placeholder_names_are_preserved_verbatim() {
        // \w+ 不匹配 user-id / a b / 中文：占位符语法不成立 → 原样保留
        assertThat(render("{{user-id}} {{a b}} {{中文名}}", Map.of()))
                .isEqualTo("{{user-id}} {{a b}} {{中文名}}");
    }

    @Test
    void null_template_renders_to_empty_string() {
        assertThat(render(null, Map.of("name", "x"))).isEmpty();
    }

    @Test
    void plain_text_without_placeholder_passes_through() {
        assertThat(render("no placeholders here", Map.of())).isEqualTo("no placeholders here");
    }

    // ---- preview：mock mapper（含 channel_content 分支）----

    private static TemplateService serviceWithMapper(NfyaTemplateMapper mapper) throws Exception {
        TemplateService service = new TemplateService(new ObjectMapper());
        Field baseMapper = com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class
                .getDeclaredField("baseMapper");
        baseMapper.setAccessible(true);
        baseMapper.set(service, mapper);
        return service;
    }

    private static NfyaTemplate template(long id, String titleTpl, String contentTpl, String channelContent) {
        NfyaTemplate t = new NfyaTemplate();
        t.setId(id);
        t.setTenantId(1L);
        t.setTemplateCode("TPL_CODE");
        t.setTitleTpl(titleTpl);
        t.setContentTpl(contentTpl);
        t.setChannelContent(channelContent);
        return t;
    }

    @Test
    void preview_renders_global_and_per_channel_overrides() throws Exception {
        NfyaTemplateMapper mapper = mock(NfyaTemplateMapper.class);
        NfyaTemplate t = template(9L, "Hi {{name}}", "body {{name}} {{count}}",
                "{\"DINGTALK\":{\"title_tpl\":\"DT {{name}}\"},\"FEISHU\":{},\"BAD\":\"plain-string\"}");
        when(mapper.selectOne(any())).thenReturn(t);
        when(mapper.selectOne(any(), anyBoolean())).thenReturn(t);
        when(mapper.selectList(any())).thenReturn(List.of(t));

        Map<String, Object> data = serviceWithMapper(mapper)
                .preview(1L, "TPL_CODE", null, Map.of("name", "Justin", "count", 3));

        assertThat(data.get("template_id")).isEqualTo("9");
        assertThat(data.get("title")).isEqualTo("Hi Justin");
        assertThat(data.get("content")).isEqualTo("body Justin 3");

        @SuppressWarnings("unchecked")
        Map<String, Object> perChannel = (Map<String, Object>) data.get("channel_content");
        // DINGTALK 覆盖 title、content 回落全局；FEISHU 空覆盖全回落；非 Map 值 "plain-string" 跳过
        assertThat(perChannel).containsOnlyKeys("DINGTALK", "FEISHU");
        assertThat(perChannel.get("DINGTALK")).isEqualTo(Map.of("title", "DT Justin", "content", "body Justin 3"));
        assertThat(perChannel.get("FEISHU")).isEqualTo(Map.of("title", "Hi Justin", "content", "body Justin 3"));
    }

    @Test
    void preview_missing_template_throws_10400() throws Exception {
        NfyaTemplateMapper mapper = mock(NfyaTemplateMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.selectOne(any(), anyBoolean())).thenReturn(null);
        when(mapper.selectList(any())).thenReturn(List.of());

        TemplateService service = serviceWithMapper(mapper);
        assertThatThrownBy(() -> service.preview(1L, "NOPE", null, Map.of("name", "x")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
    }
}
