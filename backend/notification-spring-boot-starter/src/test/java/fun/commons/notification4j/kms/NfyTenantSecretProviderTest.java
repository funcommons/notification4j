package fun.commons.notification4j.kms;

import fun.commons.framework4j.id.util.IdObfuscator;
import fun.commons.notification4j.entity.NfyaTenant;
import fun.commons.notification4j.mapper.NfyaTenantMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 第 26 步 kms 层单测：NfyTenantSecretProvider（Signature SecretProvider 实现）。
 * accessKey=OpenID → fromOpenId 解码雪花 id → 查 nfya_tenant → ACTIVE 才出签名密钥
 * （停用/注销租户签名面同断，评审 P0）；null/blank/非法 OpenID/查库异常一律 null（不外抛）。
 */
// VECTOR: TAG=step26-unit
class NfyTenantSecretProviderTest {

    private final NfyaTenantMapper tenantMapper = mock(NfyaTenantMapper.class);
    private final NfyTenantSecretProvider provider = new NfyTenantSecretProvider(tenantMapper);

    private static NfyaTenant tenant(String status, String secret) {
        NfyaTenant t = new NfyaTenant();
        t.setId(123L);
        t.setStatus(status);
        t.setTenantSecret(secret);
        return t;
    }

    @Test
    void active_tenant_open_id_resolves_to_plain_secret() {
        String openId = IdObfuscator.toOpenId(123L);
        when(tenantMapper.selectById(123L)).thenReturn(tenant("ACTIVE", "hmac-secret"));

        assertThat(provider.getSecret(openId)).isEqualTo("hmac-secret");
    }

    @Test
    void suspended_or_closed_tenant_gets_no_secret() {
        String openId = IdObfuscator.toOpenId(123L);
        when(tenantMapper.selectById(123L))
                .thenReturn(tenant("SUSPENDED", "hmac-secret"))
                .thenReturn(tenant("CLOSED", "hmac-secret"));

        assertThat(provider.getSecret(openId)).isNull(); // 停用即断签名面
        assertThat(provider.getSecret(openId)).isNull(); // 注销同断
    }

    @Test
    void unknown_tenant_id_returns_null() {
        when(tenantMapper.selectById(anyLong())).thenReturn(null);
        assertThat(provider.getSecret(IdObfuscator.toOpenId(999L))).isNull();
    }

    @Test
    void null_or_blank_access_key_short_circuits_without_db_access() {
        assertThat(provider.getSecret(null)).isNull();
        assertThat(provider.getSecret("  ")).isNull();
        verifyNoInteractions(tenantMapper); // 未触库
    }

    @Test
    void malformed_access_key_is_swallowed_as_null() {
        assertThat(provider.getSecret("not-an-open-id!!")).isNull(); // fromOpenId 异常 → null
    }

    @Test
    void mapper_failure_is_swallowed_as_null() {
        when(tenantMapper.selectById(anyLong())).thenThrow(new RuntimeException("db down"));
        assertThat(provider.getSecret(IdObfuscator.toOpenId(123L))).isNull(); // 签名面故障不外抛
    }
}
