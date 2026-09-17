package fun.commons.notification4j.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import fun.commons.framework4j.id.util.IdObfuscator;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.entity.NfyaMessageType;
import fun.commons.notification4j.mapper.NfyaMessageTypeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * API-PTE-005 设置类型强制订阅（mandatory 唯一设置入口，§4.4 平台域）。
 * tenant_open_id 解码为内部雪花 id（不出网）；10400 租户不存在/类型不存在（防探测同码）。
 * mandatory 生效面：SubscriptionService.enforceMandatory（订阅 PUT 强制集校验）。
 */
@Service
@RequiredArgsConstructor
public class PlatformTypeService {

    private final NfyaMessageTypeMapper messageTypeMapper;

    @Transactional
    public Map<String, Object> setTypeMandatory(String tenantOpenId, String typeCode, Integer mandatory) {
        if (mandatory == null || (mandatory != 0 && mandatory != 1)) {
            throw new ApiException(10100, "mandatory 仅允许 0/1");
        }
        long tenantId;
        try {
            tenantId = IdObfuscator.fromOpenId(tenantOpenId);
        } catch (Exception e) {
            throw new ApiException(10400, "租户不存在");
        }
        NfyaMessageType type = messageTypeMapper.selectOne(new LambdaQueryWrapper<NfyaMessageType>()
                .eq(NfyaMessageType::getTenantId, tenantId)
                .eq(NfyaMessageType::getTypeCode, typeCode)
                .last("LIMIT 1"));
        if (type == null) {
            throw new ApiException(10400, "类型不存在");
        }
        type.setMandatory(mandatory);
        messageTypeMapper.updateById(type);
        return Map.of("type_code", typeCode, "mandatory", mandatory);
    }
}
