package fun.commons.notification4j.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.entity.NfyaTenant;
import fun.commons.notification4j.entity.NfypRegistrationKey;
import fun.commons.notification4j.mapper.NfyaTenantMapper;
import fun.commons.notification4j.mapper.NfypRegistrationKeyMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 24 步纯函数层单测：RegistrationKeyService 校验与 mask 纯逻辑（mock 双 mapper）。
 * 分支：maskCode（null/≤8/9+/前 8 保留）；issue 缺省与钳制（maxUses/expireHours null→1/24、≤0→1）+
 * 随机码形态 + preset 缺省；register 空码 10608 / 码不存在 10608 / 原子扣减失败 10608 /
 * 成功路径（mock insert 回填雪花 id 模拟 keyGenerator；toOpenId/secret 形态断言）。
 */
// VECTOR: TAG=step24-unit
class RegistrationKeyServiceTest {

    private final NfypRegistrationKeyMapper keyMapper = mock(NfypRegistrationKeyMapper.class);
    private final NfyaTenantMapper tenantMapper = mock(NfyaTenantMapper.class);
    private final RegistrationKeyService service =
            new RegistrationKeyService(keyMapper, tenantMapper, new ObjectMapper());

    // ---- issue（签发默认值与钳制）----

    @Test
    void issue_defaults_one_use_24h_and_random_code_shape() {
        Map<String, Object> data = service.issue(null, null, null, null);

        ArgumentCaptor<NfypRegistrationKey> captor = ArgumentCaptor.forClass(NfypRegistrationKey.class);
        verify(keyMapper).insert(captor.capture());
        NfypRegistrationKey saved = captor.getValue();
        assertThat(saved.getCode()).matches("nfy-[0-9a-f]{20}"); // randomHex(20)
        assertThat(data.get("registration_key")).isEqualTo(saved.getCode());
        assertThat(data.get("max_uses")).isEqualTo(1);
        assertThat(data.get("expire_hours")).isEqualTo(24);
        assertThat(saved.getMaxUses()).isEqualTo(1);
        assertThat(saved.getUsedCount()).isZero();
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getPreset()).isEqualTo("{}");
        assertThat(saved.getConsumedTenantId()).isZero();
        assertThat(saved.getIssueBy()).isEmpty(); // null → ""（防 NULL 列）
        assertThat(saved.getExpireAt()).isNotNull();
    }

    @Test
    void issue_clamps_non_positive_values_and_keeps_preset_and_issuer() {
        service.issue(0L, -5L, Map.of("privileges", Map.of("k", "v")), "admin-1");

        ArgumentCaptor<NfypRegistrationKey> captor = ArgumentCaptor.forClass(NfypRegistrationKey.class);
        verify(keyMapper).insert(captor.capture());
        NfypRegistrationKey saved = captor.getValue();
        assertThat(saved.getMaxUses()).isEqualTo(1);
        assertThat(data_hours(saved)).isEqualTo(1);
        assertThat(saved.getPreset()).contains("privileges");
        assertThat(saved.getIssueBy()).isEqualTo("admin-1");
    }

    private int data_hours(NfypRegistrationKey saved) {
        // expireHours≤0 → Math.max(1, ·) = 1：经返回值断言（expireAt=now+1h 与时钟耦合，不直接比时刻）
        return (int) service.issue(1L, 0L, null, null).get("expire_hours");
    }

    // ---- register：空码/不存在/扣减失败 10608 同码防探测 ----

    @Test
    void register_blank_code_throws_10608_without_db() {
        assertThatThrownBy(() -> service.register(null, "t")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo(10608));
        assertThatThrownBy(() -> service.register("   ", "t")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo(10608));
    }

    @Test
    void register_unknown_code_throws_10608() {
        when(keyMapper.selectOne(any())).thenReturn(null);
        when(keyMapper.selectOne(any(), anyBoolean())).thenReturn(null);
        assertThatThrownBy(() -> service.register("nfy-nope", "t")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo(10608));
    }

    @Test
    void register_exhausted_key_throws_10608_when_consume_loses_race() {
        NfypRegistrationKey key = new NfypRegistrationKey();
        key.setCode("nfy-race");
        when(keyMapper.selectOne(any())).thenReturn(key);
        when(keyMapper.selectOne(any(), anyBoolean())).thenReturn(key);
        when(tenantMapper.insert(any(NfyaTenant.class))).thenAnswer(inv -> {
            inv.<NfyaTenant>getArgument(0).setId(123L); // 模拟 MP 雪花 id 回填
            return 1;
        });
        when(keyMapper.consume(anyString(), anyLong())).thenReturn(0); // 原子扣减失败

        assertThatThrownBy(() -> service.register("nfy-race", "t")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo(10608));
    }

    @Test
    void register_success_returns_open_id_and_one_time_secret() {
        NfypRegistrationKey key = new NfypRegistrationKey();
        key.setCode("nfy-ok");
        key.setPreset(null); // 无 preset → 跳过预绑
        when(keyMapper.selectOne(any())).thenReturn(key);
        when(keyMapper.selectOne(any(), anyBoolean())).thenReturn(key);
        when(tenantMapper.insert(any(NfyaTenant.class))).thenAnswer(inv -> {
            inv.<NfyaTenant>getArgument(0).setId(123L);
            return 1;
        });
        when(keyMapper.consume(anyString(), anyLong())).thenReturn(1);

        Map<String, Object> data = service.register("nfy-ok", "  "); // 空白名 → 缺省名

        assertThat(String.valueOf(data.get("open_id"))).isNotBlank();
        String secret = String.valueOf(data.get("tenant_secret"));
        assertThat(secret).matches("[0-9a-f]{48}"); // 24+24 hex 明文一次
        ArgumentCaptor<NfyaTenant> captor = ArgumentCaptor.forClass(NfyaTenant.class);
        verify(tenantMapper).insert(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("注册码租户");
        assertThat(captor.getValue().getEmail()).isNull(); // TCK 唯一索引谓词 email IS NOT NULL：禁空串
    }

    // ---- maskCode 静态纯逻辑 ----

    @Test
    void mask_code_null_and_short_are_fully_masked() {
        assertThat(RegistrationKeyService.maskCode(null)).isEqualTo("****");
        assertThat(RegistrationKeyService.maskCode("nfy-1")).isEqualTo("****");
        assertThat(RegistrationKeyService.maskCode("12345678")).isEqualTo("****"); // ≤8 全掩码
    }

    @Test
    void mask_code_keeps_first_eight_chars() {
        assertThat(RegistrationKeyService.maskCode("123456789")).isEqualTo("12345678****");
        assertThat(RegistrationKeyService.maskCode("nfy-0123456789abcdef0123"))
                .isEqualTo("nfy-0123****");
    }
}
