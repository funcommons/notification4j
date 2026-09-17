package fun.commons.notification4j.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.entity.NfyaTemplate;
import fun.commons.notification4j.mapper.NfyaTemplateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板域（API-TPL-001/002/003，§5.9.2；V1.1 契约提前落地）。
 * {{param}} 占位符渲染；参数缺失 → 10603「模板参数缺失:{name}」；
 * channel_content 分渠道覆盖（{DINGTALK:{title_tpl,content_tpl}}）；
 * template_code 租户内唯一（uk 10401），逻辑删后同 code 可重建（部分唯一索引）。
 */
@Service
@RequiredArgsConstructor
public class TemplateService extends ServiceImpl<NfyaTemplateMapper, NfyaTemplate> {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private final ObjectMapper objectMapper;

    @Transactional
    public Map<String, Object> create(long tenantId, Map<String, Object> req) {
        // 动态 schema（Map body）手工必填校验：缺省任一必填列会被 DDL NOT NULL 击穿成 500（评审 P2）
        for (String field : List.of("template_code", "name", "type_code", "title_tpl", "content_tpl")) {
            Object v = req.get(field);
            if (v == null || String.valueOf(v).isBlank()) {
                throw new ApiException(10100, field + " 不能为空");
            }
        }
        NfyaTemplate t = new NfyaTemplate();
        t.setTenantId(tenantId);
        t.setTemplateCode(str(req.get("template_code")));
        t.setName(str(req.get("name")));
        t.setTypeCode(str(req.get("type_code")));
        t.setTitleTpl(str(req.get("title_tpl")));
        t.setContentTpl(str(req.get("content_tpl")));
        t.setChannelContent(toJson((Map<String, Object>) req.get("channel_content")));
        t.setStatus("ENABLED");
        t.setExt("{}");
        try {
            save(t);
        } catch (DuplicateKeyException e) {
            throw new ApiException(10401, "模板编码已存在");
        }
        return Map.of("template_id", String.valueOf(t.getId()));
    }

    public Map<String, Object> list(long tenantId) {
        List<NfyaTemplate> rows = list(new LambdaQueryWrapper<NfyaTemplate>()
                .eq(NfyaTemplate::getTenantId, tenantId)
                .orderByDesc(NfyaTemplate::getCreatedAt));
        return Map.of("list", rows.stream().map(this::toVo).toList());
    }

    public Map<String, Object> detail(long tenantId, String templateId) {
        return toVo(requireOwned(tenantId, templateId));
    }

    @Transactional
    public Map<String, Object> patch(long tenantId, String templateId, Map<String, Object> req) {
        NfyaTemplate t = requireOwned(tenantId, templateId);
        if (req.get("name") != null) t.setName(str(req.get("name")));
        if (req.get("title_tpl") != null) t.setTitleTpl(str(req.get("title_tpl")));
        if (req.get("content_tpl") != null) t.setContentTpl(str(req.get("content_tpl")));
        if (req.get("channel_content") != null) {
            t.setChannelContent(toJson((Map<String, Object>) req.get("channel_content")));
        }
        if (req.get("status") != null) {
            String status = str(req.get("status"));
            if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
                throw new ApiException(10100, "status 仅允许 ENABLED/DISABLED");
            }
            t.setStatus(status);
        }
        updateById(t);
        return Map.of("template_id", templateId);
    }

    /** 逻辑删（删除后同 code 可重建——部分唯一索引口径） */
    @Transactional
    public Map<String, Object> delete(long tenantId, String templateId) {
        requireOwned(tenantId, templateId);
        removeById(Long.valueOf(templateId));
        return Map.of("template_id", templateId);
    }

    /**
     * TPL-003 渲染预览：{{param}} 替换；缺失参数 → 10603（首个缺失名入 message）；
     * channel_content 按渠道覆盖后在渠道段输出（钉钉/企微/飞书分段）。
     */
    public Map<String, Object> preview(long tenantId, String templateCode, String typeCode, Map<String, Object> params) {
        NfyaTemplate t = getOne(new LambdaQueryWrapper<NfyaTemplate>()
                .eq(NfyaTemplate::getTenantId, tenantId)
                .eq(NfyaTemplate::getTemplateCode, templateCode)
                .last("LIMIT 1"));
        if (t == null) {
            throw new ApiException(10400, "模板不存在");
        }
        Map<String, Object> params0 = params == null ? Map.of() : params;
        String title = render(t.getTitleTpl(), params0);
        String content = render(t.getContentTpl(), params0);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("template_id", String.valueOf(t.getId()));
        data.put("title", title);
        data.put("content", content);

        Map<String, Object> channelContent = parseObj(t.getChannelContent());
        Map<String, Object> perChannel = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : channelContent.entrySet()) {
            if (!(e.getValue() instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> override = (Map<String, Object>) e.getValue();
            String cTitle = override.get("title_tpl") == null ? title
                    : render(str(override.get("title_tpl")), params0);
            String cContent = override.get("content_tpl") == null ? content
                    : render(str(override.get("content_tpl")), params0);
            perChannel.put(e.getKey(), Map.of("title", cTitle, "content", cContent));
        }
        if (!perChannel.isEmpty()) {
            data.put("channel_content", perChannel);
        }
        return data;
    }

    /** 渲染：缺参抛 10603（契约错误码，message 携带首个缺失参数名） */
    private String render(String tpl, Map<String, Object> params) {
        Matcher m = PLACEHOLDER.matcher(tpl == null ? "" : tpl);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            Object v = params.get(name);
            if (v == null) {
                throw new ApiException(10603, "模板参数缺失:" + name);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(v)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private NfyaTemplate requireOwned(long tenantId, String templateId) {
        long id;
        try {
            id = Long.parseLong(templateId);
        } catch (NumberFormatException e) {
            throw new ApiException(10400, "模板不存在");
        }
        NfyaTemplate t = getById(id);
        if (t == null || t.getTenantId() != tenantId) {
            throw new ApiException(10400, "模板不存在");
        }
        return t;
    }

    private Map<String, Object> toVo(NfyaTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("template_id", String.valueOf(t.getId()));
        m.put("template_code", t.getTemplateCode());
        m.put("name", t.getName());
        m.put("type_code", t.getTypeCode());
        m.put("title_tpl", t.getTitleTpl());
        m.put("content_tpl", t.getContentTpl());
        m.put("channel_content", parseObj(t.getChannelContent()));
        m.put("status", t.getStatus());
        m.put("created_at", t.getCreatedAt() == null ? null : t.getCreatedAt().toInstant().toEpochMilli());
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseObj(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String toJson(Map<String, Object> v) {
        if (v == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(v);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
