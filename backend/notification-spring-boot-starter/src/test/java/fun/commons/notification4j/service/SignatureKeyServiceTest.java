package fun.commons.notification4j.service;

import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.entity.NfyaTenant;
import fun.commons.notification4j.mapper.NfyaTenantMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 24 步纯函数层单测：SignatureKeyService 校验与脱敏（mock NfyaTenantMapper，不起 Spring/DB）。
 * 分支：rotate 长度四向（null/空��/15 下界外/72 上界外）10100 前置拦截（不触 DB）；
 * rotate 首次与轮换（prev 携带 + prev_at）；getStatus configured/has_prev/prev_at；
 * mask 静态纯逻辑（null/空白/≤8 全掩码/前后 2 保留）。
 */
// VECTOR: TAG=step24-unit
class SignatureKeyServiceTest {

    private final NfyaTenantMapper tenantMapper = mock(NfyaTenantMapper.class);
    private final SignatureKeyService service = new SignatureKeyService(tenantMapper);

    private static NfyaTenant tenant(String secret, String prev, OffsetDateTime prevAt) {
        NfyaTenant t = new NfyaTenant();
        t.setId(1L);
        t.setTenantSecret(secret);
        t.setTenantSecretPrev(prev);
        t.setTenantSecretPrevAt(prevAt);
        return t;
    }

    // ---- rotate：长度前置校验（requireTenant 之前，mapper 不触）----

    @Test
    void rotate_rejects_null_blank_and_out_of_range_lengths_with_10100() {
        for (String bad : new String[]{null, "   ", "a".repeat(15), "a".repeat(72)}) {
            assertThatThrownBy(() -> service.rotate(1L, bad))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(10100);
                        assertThat(e.getMessage()).contains("16~71");
                    });
        }
    }

    @Test
    void rotate_boundary_lengths_pass_validation() {
        when(tenantMapper.selectById(1L)).thenReturn(tenant(null, null, null));
        // 下界 16 / 上界 71 恰好合法（其余分支不触发 10100）
        assertThatCode(() -> service.rotate(1L, "a".repeat(16))).doesNotThrowAnyException();
        assertThatCode(() -> service.rotate(1L, "b".repeat(71))).doesNotThrowAnyException();
    }

    @Test
    void rotate_first_time_sets_secret_without_prev() {
        when(tenantMapper.selectById(1L)).thenReturn(tenant(null, null, null));

        Map<String, Object> data = service.rotate(1L, "abcdefghijklmnop");

        assertThat(data.get("rotated")).isEqualTo(true);
        assertThat(data.get("masked")).isEqualTo("ab****op");
        ArgumentCaptor<NfyaTenant> captor = ArgumentCaptor.forClass(NfyaTenant.class);
        verify(tenantMapper).updateById(captor.capture());
        NfyaTenant saved = captor.getValue();
        assertThat(saved.getTenantSecret()).isEqualTo("abcdefghijklmnop");
        assertThat(saved.getTenantSecretPrev()).isNull();
        assertThat(saved.getTenantSecretPrevAt()).isNull();
        // jsonb 四列置 null 借 MP NOT_NULL 更新策略从 SET 剔除（防 jsonb 冲突）
        assertThat(saved.getPrivileges()).isNull();
        assertThat(saved.getConfig()).isNull();
        assertThat(saved.getOem()).isNull();
        assertThat(saved.getExt()).isNull();
    }

    @Test
    void rotate_with_existing_secret_carries_prev_and_prev_at() {
        OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);
        when(tenantMapper.selectById(1L)).thenReturn(tenant("old-secret-value-16", null, null));

        service.rotate(1L, "new-secret-value-16");

        ArgumentCaptor<NfyaTenant> captor = ArgumentCaptor.forClass(NfyaTenant.class);
        verify(tenantMapper).updateById(captor.capture());
        NfyaTenant saved = captor.getValue();
        assertThat(saved.getTenantSecret()).isEqualTo("new-secret-value-16");
        assertThat(saved.getTenantSecretPrev()).isEqualTo("old-secret-value-16");
        assertThat(saved.getTenantSecretPrevAt()).isAfter(before);
    }

    @Test
    void rotate_unknown_tenant_throws_10400() {
        when(tenantMapper.selectById(any())).thenReturn(null);
        assertThatThrownBy(() -> service.rotate(99L, "a".repeat(16)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
    }

    // ---- getStatus ----

    @Test
    void get_status_reports_configured_masked_and_prev() {
        when(tenantMapper.selectById(1L))
                .thenReturn(tenant("abcdefghijklmnop", "prev-secret-value", OffsetDateTime.parse("2026-06-15T08:00:00Z")));

        Map<String, Object> data = service.getStatus(1L);

        assertThat(data.get("configured")).isEqualTo(true);
        assertThat(data.get("masked")).isEqualTo("ab****op");
        assertThat(data.get("has_prev")).isEqualTo(true);
        assertThat(data.get("prev_at"))
                .isEqualTo(OffsetDateTime.parse("2026-06-15T08:00:00Z").toInstant().toEpochMilli());
    }

    @Test
    void get_status_unconfigured_tenant() {
        when(tenantMapper.selectById(2L)).thenReturn(tenant(null, null, null));
        Map<String, Object> data = service.getStatus(2L);
        assertThat(data.get("configured")).isEqualTo(false);
        assertThat(data.get("masked")).isNull();
        assertThat(data.get("has_prev")).isEqualTo(false);
        assertThat(data.get("prev_at")).isNull();
    }

    // ---- mask 静态纯逻辑 ----

    @Test
    void mask_null_blank_and_short_secrets() {
        assertThat(SignatureKeyService.mask(null)).isNull();
        assertThat(SignatureKeyService.mask("")).isNull();
        assertThat(SignatureKeyService.mask("   ")).isNull();
        assertThat(SignatureKeyService.mask("abc")).isEqualTo("****");
        assertThat(SignatureKeyService.mask("12345678")).isEqualTo("****"); // ≤8 全掩码
    }

    @Test
    void mask_keeps_first_and_last_two_chars() {
        assertThat(SignatureKeyService.mask("abcdefghi")).isEqualTo("ab****hi"); // 9 = 最短露字
        assertThat(SignatureKeyService.mask("abcdefghijklmnop")).isEqualTo("ab****op");
    }
}
