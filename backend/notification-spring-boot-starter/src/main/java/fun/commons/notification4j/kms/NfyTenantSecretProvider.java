package fun.commons.notification4j.kms;

import fun.commons.notification4j.entity.NfyaTenant;
import fun.commons.notification4j.mapper.NfyaTenantMapper;
import fun.commons.framework4j.id.util.IdObfuscator;
import fun.commons.framework4j.signature.service.SecretProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Signature 模块的 SecretProvider 实现
 * <p>
 * 三方调用 runtime API 时 X-Access-Key = tenant_id (OpenID 字符串),
 * 本 provider 解码为雪花 id 查 nfya_tenant, 返回 tenant_secret 明文
 * (EncryptedFieldTypeHandler 读时自动解密, 用作 HMAC-SHA256 签名密钥)。
 * <p>
 * 覆盖 framework4j 默认的 InMemorySecretProvider (@ConditionalOnMissingBean)。
 */
@Component
@RequiredArgsConstructor
public class NfyTenantSecretProvider implements SecretProvider {

    private static final Logger log = LoggerFactory.getLogger(NfyTenantSecretProvider.class);

    private final NfyaTenantMapper applicationMapper;

    @Override
    public String getSecret(String accessKey) {
        if (accessKey == null || accessKey.isBlank()) return null;
        try {
            Long tenantId = IdObfuscator.fromOpenId(accessKey);
            NfyaTenant app = applicationMapper.selectById(tenantId);
            // 非 ACTIVE（SUSPENDED/CLOSED）租户不出签名密钥（评审第 10~16 步 P0：租户停用后签名面同断）
            if (app == null || !"ACTIVE".equals(app.getStatus())) {
                log.warn("[Signature] accessKey={} 解码 tenantId={} 无对应 ACTIVE application", accessKey, tenantId);
                return null;
            }
            // tenant_secret 走 EncryptedFieldTypeHandler 自动解密返回明文 HMAC 密钥
            return app.getTenantSecret();
        } catch (Exception e) {
            log.warn("[Signature] getSecret 失败 accessKey={}: {}", accessKey, e.getMessage());
            return null;
        }
    }
}
