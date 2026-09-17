package fun.commons.notification4j.service;

import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.dto.PatchChannelsChannelIdRequest;
import fun.commons.notification4j.dto.PostChannelsRequest;
import fun.commons.notification4j.entity.NfyaChannel;
import fun.commons.notification4j.mapper.NfyaChannelMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 25 步 service 层 mock 单测：ChannelCoreService（USER/TENANT 渠道注册/验证/启停公共核心）。
 * 纯 mock NfyaChannelMapper + WebhookTargetValidator + ChannelVerifier（外呼/SSRF 均不真发）。
 * 分支：register（校验前置→USER 上限 10605→insert 唯一闸 10401→EMAIL 归一化→PENDING 落库→
 * TENANT 免上限）/verify（owner 10400 两面文案→verifier 桩→ENABLED+清零+last_verify_at）/
 * patch（10610 两条件/10100 非法 status/空 name 跳过）/requireOwned（非数字/跨租户/跨 scope/userid 比对/TENANT 豁免）。
 */
// VECTOR: TAG=step25-unit
class ChannelCoreServiceTest {

    private final NfyaChannelMapper channelMapper = mock(NfyaChannelMapper.class);
    private final WebhookTargetValidator validator = mock(WebhookTargetValidator.class);
    private final ChannelVerifier verifier = mock(ChannelVerifier.class);
    private final ChannelCoreService core = new ChannelCoreService(channelMapper, validator, verifier);

    private static PostChannelsRequest req(String type, String target) {
        return new PostChannelsRequest(type, "我的渠道", target, null, null);
    }

    private static NfyaChannel owned(long tenantId, String userid, String scope) {
        NfyaChannel ch = new NfyaChannel();
        ch.setId(9L);
        ch.setTenantId(tenantId);
        ch.setUserid(userid);
        ch.setScope(scope);
        ch.setChannelType("DINGTALK");
        ch.setStatus("PENDING");
        return ch;
    }

    // ---- register ----

    @Test
    void register_user_scope_email_normalized_and_pending() {
        when(channelMapper.selectCount(any())).thenReturn(0L);
        var data = core.register(1L, "u1", ChannelCoreService.SCOPE_USER,
                new PostChannelsRequest("EMAIL", "邮箱", "  A@X.Com ", "s3cret", "kw"));

        ArgumentCaptor<NfyaChannel> cap = ArgumentCaptor.forClass(NfyaChannel.class);
        verify(channelMapper).insert(cap.capture());
        NfyaChannel saved = cap.getValue();
        assertThat(saved.getTarget()).isEqualTo("a@x.com"); // EMAIL trim+lowercase 防变体绕唯一闸
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getFailCount()).isZero();
        assertThat(saved.getSecret()).isEqualTo("s3cret");
        assertThat(saved.getKeyword()).isEqualTo("kw");
        assertThat(saved.getScope()).isEqualTo("USER");
        assertThat(saved.getUserid()).isEqualTo("u1");
        assertThat(saved.getExt()).isEqualTo("{}");
        assertThat(data.get("channel_id")).isEqualTo(String.valueOf(saved.getId()));
        assertThat(data.get("status")).isEqualTo("PENDING");
        assertThat((String) data.get("verify_tip")).contains("verify");
    }

    @Test
    void register_non_email_target_kept_verbatim() {
        when(channelMapper.selectCount(any())).thenReturn(0L);
        core.register(1L, "u1", ChannelCoreService.SCOPE_USER,
                new PostChannelsRequest("DINGTALK", "钉钉", "  HTTPS://OAPI/x ", null, null));
        ArgumentCaptor<NfyaChannel> cap = ArgumentCaptor.forClass(NfyaChannel.class);
        verify(channelMapper).insert(cap.capture());
        assertThat(cap.getValue().getTarget()).isEqualTo("  HTTPS://OAPI/x ");
        assertThat(cap.getValue().getSecret()).isEmpty(); // null secret 落 ""
        assertThat(cap.getValue().getKeyword()).isEmpty();
    }

    @Test
    void register_user_scope_over_limit_throws_10605() {
        when(channelMapper.selectCount(any())).thenReturn(5L);
        assertThatThrownBy(() -> core.register(1L, "u1", ChannelCoreService.SCOPE_USER, req("EMAIL", "a@x.com")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10605));
        verify(channelMapper, never()).insert(any(NfyaChannel.class));
    }

    @Test
    void register_tenant_scope_skips_user_limit() {
        var data = core.register(1L, "", ChannelCoreService.SCOPE_TENANT, req("FEISHU", "https://feishu.cn/hook/t"));
        verify(channelMapper, never()).selectCount(any()); // TENANT 公共资源不设 ≤5 上限
        ArgumentCaptor<NfyaChannel> cap = ArgumentCaptor.forClass(NfyaChannel.class);
        verify(channelMapper).insert(cap.capture());
        assertThat(cap.getValue().getScope()).isEqualTo("TENANT");
        assertThat(data.get("status")).isEqualTo("PENDING");
    }

    @Test
    void register_duplicate_target_unique_gate_throws_10401() {
        when(channelMapper.selectCount(any())).thenReturn(0L);
        when(channelMapper.insert(any(NfyaChannel.class))).thenThrow(new DuplicateKeyException("uk"));
        assertThatThrownBy(() -> core.register(1L, "u1", ChannelCoreService.SCOPE_USER, req("EMAIL", "a@x.com")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10401);
                    assertThat(e.getMessage()).isEqualTo("该渠道已存在");
                });
    }

    @Test
    void register_target_validation_runs_before_any_write() {
        doThrow(new ApiException(10609, "Webhook 地址非法或非官方域名")).when(validator).validate("DINGTALK", "https://evil");
        assertThatThrownBy(() -> core.register(1L, "u1", ChannelCoreService.SCOPE_USER,
                new PostChannelsRequest("DINGTALK", "钉", "https://evil", null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10609));
        verify(channelMapper, never()).insert(any(NfyaChannel.class));
        verify(channelMapper, never()).selectCount(any());
    }

    // ---- verify ----

    @Test
    void verify_success_enables_and_resets_fail_count() {
        NfyaChannel ch = owned(1L, "u1", ChannelCoreService.SCOPE_USER);
        ch.setFailCount(3);
        when(channelMapper.selectById(9L)).thenReturn(ch);
        var data = core.verify(1L, "u1", ChannelCoreService.SCOPE_USER, "9", "资源不存在或无权访问");

        verify(verifier).verify(ch);
        ArgumentCaptor<NfyaChannel> cap = ArgumentCaptor.forClass(NfyaChannel.class);
        verify(channelMapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("ENABLED");
        assertThat(cap.getValue().getFailCount()).isZero();
        assertThat(cap.getValue().getLastVerifyAt()).isNotNull();
        assertThat(data.get("status")).isEqualTo("ENABLED");
        assertThat(data.get("last_verify_at"))
                .isEqualTo(cap.getValue().getLastVerifyAt().toInstant().toEpochMilli());
    }

    @Test
    void verify_verifier_failure_blocks_state_change() {
        when(channelMapper.selectById(9L)).thenReturn(owned(1L, "u1", ChannelCoreService.SCOPE_USER));
        doThrow(new ApiException(10604, "渠道验证失败:HTTP 500")).when(verifier).verify(any(NfyaChannel.class));
        assertThatThrownBy(() -> core.verify(1L, "u1", ChannelCoreService.SCOPE_USER, "9", "x"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10604));
        verify(channelMapper, never()).updateById(any(NfyaChannel.class));
    }

    // ---- patch ----

    @Test
    void patch_renames_only_keeps_status() {
        NfyaChannel ch = owned(1L, "u1", ChannelCoreService.SCOPE_USER);
        ch.setStatus("DISABLED");
        when(channelMapper.selectById(9L)).thenReturn(ch);
        var data = core.patch(1L, "u1", ChannelCoreService.SCOPE_USER, "9",
                new PatchChannelsChannelIdRequest("新名字", null), "x");
        ArgumentCaptor<NfyaChannel> cap = ArgumentCaptor.forClass(NfyaChannel.class);
        verify(channelMapper).updateById(cap.capture());
        assertThat(cap.getValue().getName()).isEqualTo("新名字");
        assertThat(cap.getValue().getStatus()).isEqualTo("DISABLED");
        assertThat(data.get("status")).isEqualTo("DISABLED");
    }

    @Test
    void patch_blank_name_ignored() {
        NfyaChannel ch = owned(1L, "u1", ChannelCoreService.SCOPE_USER);
        ch.setName("旧名");
        when(channelMapper.selectById(9L)).thenReturn(ch);
        core.patch(1L, "u1", ChannelCoreService.SCOPE_USER, "9",
                new PatchChannelsChannelIdRequest("   ", null), "x");
        assertThat(ch.getName()).isEqualTo("旧名");
    }

    @Test
    void patch_illegal_status_throws_10100() {
        when(channelMapper.selectById(9L)).thenReturn(owned(1L, "u1", ChannelCoreService.SCOPE_USER));
        assertThatThrownBy(() -> core.patch(1L, "u1", ChannelCoreService.SCOPE_USER, "9",
                new PatchChannelsChannelIdRequest(null, "PAUSED"), "x"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10100);
                    assertThat(e.getMessage()).contains("ENABLED/DISABLED");
                });
    }

    @Test
    void patch_enable_without_prior_verify_throws_10610() {
        when(channelMapper.selectById(9L)).thenReturn(owned(1L, "u1", ChannelCoreService.SCOPE_USER));
        assertThatThrownBy(() -> core.patch(1L, "u1", ChannelCoreService.SCOPE_USER, "9",
                new PatchChannelsChannelIdRequest(null, "ENABLED"), "x"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10610));
        verify(channelMapper, never()).updateById(any(NfyaChannel.class));
    }

    @Test
    void patch_enable_with_burnout_fail_count_throws_10610() {
        NfyaChannel ch = owned(1L, "u1", ChannelCoreService.SCOPE_USER);
        ch.setLastVerifyAt(OffsetDateTime.now());
        ch.setFailCount(5); // 熔断态：已验证过但连续失败 ≥5
        when(channelMapper.selectById(9L)).thenReturn(ch);
        assertThatThrownBy(() -> core.patch(1L, "u1", ChannelCoreService.SCOPE_USER, "9",
                new PatchChannelsChannelIdRequest(null, "ENABLED"), "x"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10610));
    }

    // ND-L5-01（P1）EMAIL 直启/熔断重启用豁免：IM（DINGTALK/WECOM/FEISHU）未验证 10610 / 熔断 10610
    // 由上方 patch_enable_without_prior_verify_throws_10610、patch_enable_with_burnout_fail_count_throws_10610 钉死不变。

    @Test
    void patch_email_pending_enable_succeeds_without_verify() {
        // EMAIL verify 恒 10604「首次投递时校验」→ last_verify_at 无通路可置上；
        // 豁免已验证前置是 EMAIL 纯 API 唯一启用通路（PENDING 直启放行）
        NfyaChannel ch = owned(1L, "u1", ChannelCoreService.SCOPE_USER);
        ch.setChannelType("EMAIL"); // 从未验证：lastVerifyAt=null
        when(channelMapper.selectById(9L)).thenReturn(ch);
        var data = core.patch(1L, "u1", ChannelCoreService.SCOPE_USER, "9",
                new PatchChannelsChannelIdRequest(null, "ENABLED"), "x");
        verify(channelMapper).updateById(ch);
        assertThat(ch.getStatus()).isEqualTo("ENABLED");
        assertThat(data.get("status")).isEqualTo("ENABLED");
    }

    @Test
    void patch_email_burnout_reenable_counts_as_reverify_and_resets_fail_count() {
        // EMAIL 熔断态（fail_count≥5 自动停用）显式重新启用即视为重新验证：ENABLED 同时 fail_count 归 0
        NfyaChannel ch = owned(1L, "u1", ChannelCoreService.SCOPE_USER);
        ch.setChannelType("EMAIL");
        ch.setFailCount(5);
        when(channelMapper.selectById(9L)).thenReturn(ch);
        var data = core.patch(1L, "u1", ChannelCoreService.SCOPE_USER, "9",
                new PatchChannelsChannelIdRequest(null, "ENABLED"), "x");
        verify(channelMapper).updateById(ch);
        assertThat(ch.getStatus()).isEqualTo("ENABLED");
        assertThat(ch.getFailCount()).isZero();
        assertThat(data.get("status")).isEqualTo("ENABLED");
    }

    @Test
    void patch_enable_verified_channel_succeeds() {
        NfyaChannel ch = owned(1L, "u1", ChannelCoreService.SCOPE_USER);
        ch.setLastVerifyAt(OffsetDateTime.now());
        ch.setFailCount(2);
        when(channelMapper.selectById(9L)).thenReturn(ch);
        var data = core.patch(1L, "u1", ChannelCoreService.SCOPE_USER, "9",
                new PatchChannelsChannelIdRequest(null, "ENABLED"), "x");
        assertThat(ch.getStatus()).isEqualTo("ENABLED");
        assertThat(data.get("status")).isEqualTo("ENABLED");
    }

    @Test
    void patch_disable_never_requires_verify() {
        NfyaChannel ch = owned(1L, "u1", ChannelCoreService.SCOPE_USER); // 从未验证
        when(channelMapper.selectById(9L)).thenReturn(ch);
        core.patch(1L, "u1", ChannelCoreService.SCOPE_USER, "9",
                new PatchChannelsChannelIdRequest(null, "DISABLED"), "x");
        assertThat(ch.getStatus()).isEqualTo("DISABLED");
    }

    // ---- requireOwned（跨租户/跨 scope/userid 比对/非数字 id，同码 10400 防探测）----

    @Test
    void requireOwned_non_numeric_id_throws_10400() {
        assertThatThrownBy(() -> core.requireOwned(1L, "u1", ChannelCoreService.SCOPE_USER, "abc", "资源不存在或无权访问"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10400);
                    assertThat(e.getMessage()).isEqualTo("资源不存在或无权访问");
                });
    }

    @Test
    void requireOwned_missing_row_throws_10400() {
        when(channelMapper.selectById(9L)).thenReturn(null);
        assertThatThrownBy(() -> core.requireOwned(1L, "u1", ChannelCoreService.SCOPE_USER, "9", "渠道不存在"))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.getMessage()).isEqualTo("渠道不存在"));
    }

    @Test
    void requireOwned_cross_tenant_throws_10400() {
        when(channelMapper.selectById(9L)).thenReturn(owned(2L, "u1", ChannelCoreService.SCOPE_USER));
        assertThatThrownBy(() -> core.requireOwned(1L, "u1", ChannelCoreService.SCOPE_USER, "9", "x"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
    }

    @Test
    void requireOwned_cross_scope_throws_10400() {
        when(channelMapper.selectById(9L)).thenReturn(owned(1L, "u1", ChannelCoreService.SCOPE_TENANT));
        assertThatThrownBy(() -> core.requireOwned(1L, "u1", ChannelCoreService.SCOPE_USER, "9", "x"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
    }

    @Test
    void requireOwned_userid_mismatch_on_user_face_throws_10400() {
        when(channelMapper.selectById(9L)).thenReturn(owned(1L, "u2", ChannelCoreService.SCOPE_USER));
        assertThatThrownBy(() -> core.requireOwned(1L, "u1", ChannelCoreService.SCOPE_USER, "9", "x"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
    }

    @Test
    void requireOwned_tenant_face_skips_userid_compare() {
        NfyaChannel ch = owned(1L, "u9", ChannelCoreService.SCOPE_TENANT);
        when(channelMapper.selectById(9L)).thenReturn(ch);
        assertThat(core.requireOwned(1L, null, ChannelCoreService.SCOPE_TENANT, "9", "渠道不存在")).isSameAs(ch);
    }
}
