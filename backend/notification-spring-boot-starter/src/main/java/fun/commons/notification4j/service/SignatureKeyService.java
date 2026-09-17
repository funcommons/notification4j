package fun.commons.notification4j.service;

import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.entity.NfyaTenant;
import fun.commons.notification4j.mapper.NfyaTenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 签名密钥管理（API-SEC-001，§4.3/§5.9.2；《租户设计》§5.5）。
 * tenant_secret 双用途：client_credentials 认证凭据 + HMAC-SHA256 签名密钥
 * （NfyTenantSecretProvider 读解密列供 fwk4j-signature 校验）。
 * 轮换：旧值进 tenant_secret_prev + prev_at（宽限 24h 由 SecretProvider 侧消费），
 * 新密钥立即用于换 token（同一列，认证链路即时生效）。
 * 输出脱敏（前 2+****+后 2），任何接口不回显完整明文。
 */
@Service
@RequiredArgsConstructor
public class SignatureKeyService {

    private static final int MIN_LEN = 16;
    // 上限 71：AES-GCM 密文=Base64(IV12+密文+tag16)，L=72 时密文 136 > varchar(132)（评审第 10~16 步 P1）
    private static final int MAX_LEN = 71;

    private final NfyaTenantMapper tenantMapper;

    /** GET 状态（脱敏） */
    public Map<String, Object> getStatus(long tenantId) {
        NfyaTenant t = requireTenant(tenantId);
        String secret = t.getTenantSecret();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configured", secret != null && !secret.isBlank());
        data.put("masked", mask(secret));
        data.put("has_prev", t.getTenantSecretPrev() != null && !t.getTenantSecretPrev().isBlank());
        data.put("prev_at", t.getTenantSecretPrevAt() == null ? null : t.getTenantSecretPrevAt().toInstant().toEpochMilli());
        return data;
    }

    /** POST 注册/轮换 */
    @Transactional
    public Map<String, Object> rotate(long tenantId, String secret) {
        if (secret == null || secret.isBlank() || secret.length() < MIN_LEN || secret.length() > MAX_LEN) {
            throw new ApiException(10100, "secret 长度须在 " + MIN_LEN + "~" + MAX_LEN + " 之间");
        }
        NfyaTenant t = requireTenant(tenantId);
        String old = t.getTenantSecret();
        // 加密列必须走实体 typeHandler（LazyEncrypted）写入——wrapper.setString 会绕过加密存明文
        // （第 11 步实测）。而 TenantEntity 的 privileges/config/oem/ext(Map+jsonb) 在 update 语句
        // 中按 varchar 发送会撞 jsonb 冲突 → 置 null 借 MP NOT_NULL 更新策略从 SET 中剔除。
        t.setPrivileges(null);
        t.setConfig(null);
        t.setOem(null);
        t.setExt(null);
        if (old != null && !old.isBlank()) {
            t.setTenantSecretPrev(old);
            t.setTenantSecretPrevAt(java.time.OffsetDateTime.now());
        }
        t.setTenantSecret(secret);
        tenantMapper.updateById(t);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rotated", true);
        data.put("masked", mask(secret));
        return data;
    }

    private NfyaTenant requireTenant(long tenantId) {
        NfyaTenant t = tenantMapper.selectById(tenantId);
        if (t == null) {
            throw new ApiException(10400, "租户不存在");
        }
        return t;
    }

    /** 脱敏：保留前 2 后 2，其余 ****；过短全掩码 */
    static String mask(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        if (secret.length() <= 8) {
            return "****";
        }
        return secret.substring(0, 2) + "****" + secret.substring(secret.length() - 2);
    }
}
