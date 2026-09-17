package fun.commons.notification4j.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.notification4j.dto.PatchTypesRequest;
import fun.commons.notification4j.dto.PostTypesRequest;
import fun.commons.notification4j.entity.NfyaMessageType;
import fun.commons.notification4j.mapper.NfyaMessageTypeMapper;
import fun.commons.framework4j.web.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 消息类型管理（F-TYP-001）：租户内唯一编码/内置类型保护/启停。
 * 错误码对齐接口文档 §6.2：10401 重复 / 10400 不存在 / 10402 内置类型不可停用。
 */
@Service
@RequiredArgsConstructor
public class MessageTypeService extends ServiceImpl<NfyaMessageTypeMapper, NfyaMessageType> {

    /** 渠道类型白名单（default_channels 元素语义校验；INAPP 哨兵恒允许） */
    private static final Set<String> CHANNEL_ENUMS = Set.of("INAPP", "DINGTALK", "WECOM", "FEISHU", "EMAIL");
    private static final Set<String> LEVEL_ENUMS = Set.of("NORMAL", "IMPORTANT", "URGENT");

    private final ObjectMapper objectMapper;

    public NfyaMessageType create(long tenantId, PostTypesRequest req) {
        NfyaMessageType t = new NfyaMessageType();
        t.setTenantId(tenantId);
        t.setTypeCode(req.typeCode());
        t.setName(req.name());
        t.setDescription(req.description() == null ? "" : req.description());
        t.setDefaultLevel(req.defaultLevel() == null ? "NORMAL" : validLevel(req.defaultLevel()));
        t.setDefaultChannels(toJsonArray(req.defaultChannels() == null ? List.of("INAPP") : req.defaultChannels()));
        t.setMandatory(0);
        t.setBuiltIn(0);
        t.setStatus("ENABLED");
        try {
            save(t);
        } catch (DuplicateKeyException e) {
            throw new ApiException(10401, "类型编码已存在");
        }
        return t;
    }

    public List<NfyaMessageType> list(long tenantId) {
        return list(new LambdaQueryWrapper<NfyaMessageType>()
                .eq(NfyaMessageType::getTenantId, tenantId)
                .orderByDesc(NfyaMessageType::getCreatedAt));
    }

    public NfyaMessageType update(long tenantId, long typeId, PatchTypesRequest req) {
        NfyaMessageType t = getById(typeId);
        if (t == null || t.getTenantId() != tenantId) {
            throw new ApiException(10400, "类型不存在");
        }
        if (req.name() != null) t.setName(req.name());
        if (req.description() != null) t.setDescription(req.description());
        if (req.defaultLevel() != null) t.setDefaultLevel(validLevel(req.defaultLevel()));
        if (req.defaultChannels() != null) t.setDefaultChannels(toJsonArray(req.defaultChannels()));
        if (req.status() != null) {
            if (!"ENABLED".equals(req.status()) && !"DISABLED".equals(req.status())) {
                throw new ApiException(10100, "status 仅允许 ENABLED/DISABLED");
            }
            if (t.getBuiltIn() == 1 && "DISABLED".equals(req.status())) {
                throw new ApiException(10402, "内置类型不可停用");
            }
            t.setStatus(req.status());
        }
        updateById(t);
        return t;
    }

    /** 发送前置校验：类型存在且属于本租户且启用（10601） */
    public NfyaMessageType requireEnabled(long tenantId, String typeCode) {
        NfyaMessageType t = getOne(new LambdaQueryWrapper<NfyaMessageType>()
                .eq(NfyaMessageType::getTenantId, tenantId)
                .eq(NfyaMessageType::getTypeCode, typeCode)
                .last("LIMIT 1"));
        if (t == null || !"ENABLED".equals(t.getStatus())) {
            throw new ApiException(10601, "消息类型不存在或已停用");
        }
        return t;
    }

    public List<Map<String, Object>> toVoList(List<NfyaMessageType> types) {
        return types.stream().map(t -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("type_id", String.valueOf(t.getId()));
            m.put("type_code", t.getTypeCode());
            m.put("name", t.getName());
            m.put("description", t.getDescription());
            m.put("default_level", t.getDefaultLevel());
            m.put("default_channels", t.getDefaultChannels());
            m.put("mandatory", t.getMandatory());
            m.put("built_in", t.getBuiltIn());
            m.put("status", t.getStatus());
            return m;
        }).toList();
    }

    private String validLevel(String level) {
        if (!LEVEL_ENUMS.contains(level)) {
            throw new ApiException(10100, "default_level 枚举非法(允许 NORMAL/IMPORTANT/URGENT)");
        }
        return level;
    }

    /** Jackson 序列化（评审第 3 步 P2：手拼 JSON 无转义，元素含引号即产出非法 jsonb）+ 枚举白名单 */
    private String toJsonArray(List<String> items) {
        if (items.isEmpty()) {
            throw new ApiException(10100, "default_channels 至少 1 个");
        }
        for (String item : items) {
            if (!CHANNEL_ENUMS.contains(item)) {
                throw new ApiException(10100, "default_channels 枚举非法(允许 INAPP/DINGTALK/WECOM/FEISHU/EMAIL)");
            }
        }
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            throw new IllegalStateException("default_channels 序列化失败", e);
        }
    }
}
