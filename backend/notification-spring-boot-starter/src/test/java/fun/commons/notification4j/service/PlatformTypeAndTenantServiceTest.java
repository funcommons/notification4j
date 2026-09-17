package fun.commons.notification4j.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.id.util.IdObfuscator;
import fun.commons.framework4j.tenant.auth.TenantSessionRevoker;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.dto.PatchTenantRequest;
import fun.commons.notification4j.dto.PostTenantsRequest;
import fun.commons.notification4j.entity.NfyaMessageType;
import fun.commons.notification4j.entity.NfyaTenant;
import fun.commons.notification4j.mapper.NfyaDeliveryMapper;
import fun.commons.notification4j.mapper.NfyaMessageTypeMapper;
import fun.commons.notification4j.mapper.NfyaTenantMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 25 步 service 层 mock 单测：PlatformTypeService（mandatory 唯一设置入口）+
 * PlatformTenantService（PTE 租户生命周期）。PTE：创建（10401 唯一/open_id 往返/密钥 48 位/
 * jsonb 列参数化更新）/列表与详情（email 脱敏 + jsonb 读）/patch（标量+jsonb 双路径）/
 * resetSecret（旧密钥进 prev）/changeStatus 状态机（SUSPEND/RESUME/CLOSE + 前置投递计数 +
 * revokeSessions 委托 revoker mock + 撤销失败不阻断）。
 * changeStatus 走 LambdaUpdateWrapper#set → NfyaTenant TableInfo 预初始化。
 */
// VECTOR: TAG=step25-unit
class PlatformTypeAndTenantServiceTest {

    private final NfyaMessageTypeMapper typeMapper = mock(NfyaMessageTypeMapper.class);
    private final NfyaTenantMapper tenantMapper = mock(NfyaTenantMapper.class);
    private final NfyaDeliveryMapper deliveryMapper = mock(NfyaDeliveryMapper.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<TenantSessionRevoker> revokerProvider = mock(ObjectProvider.class);
    private final TenantSessionRevoker revoker = mock(TenantSessionRevoker.class);

    @BeforeAll
    static void initTableInfos() {
        ServiceMockSupport.initTableInfo(NfyaTenant.class);
    }

    private PlatformTypeService newTypeService() {
        return new PlatformTypeService(typeMapper);
    }

    private PlatformTenantService newTenantService() {
        when(revokerProvider.getIfAvailable()).thenReturn(revoker);
        return new PlatformTenantService(tenantMapper, deliveryMapper, new ObjectMapper(), revokerProvider);
    }

    private static NfyaTenant tenant(long id, String status) {
        NfyaTenant t = new NfyaTenant();
        t.setId(id);
        t.setName("租户" + id);
        t.setEmail("ops@example.com");
        t.setStatus(status);
        t.setTenantSecret("old-secret");
        return t;
    }

    private static PostTenantsRequest postTenant() {
        PostTenantsRequest req = new PostTenantsRequest();
        req.setName("租户A");
        req.setEmail("a@example.com");
        return req;
    }

    // ---- PlatformTypeService ----

    @Test
    void setTypeMandatory_rejects_null_and_out_of_range() {
        assertThatThrownBy(() -> newTypeService().setTypeMandatory(IdObfuscator.toOpenId(1L), "OTC", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10100));
        assertThatThrownBy(() -> newTypeService().setTypeMandatory(IdObfuscator.toOpenId(1L), "OTC", 2))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10100));
    }

    @Test
    void setTypeMandatory_rejects_bad_open_id_and_missing_type() {
        assertThatThrownBy(() -> newTypeService().setTypeMandatory("@@", "OTC", 1))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
        when(typeMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> newTypeService().setTypeMandatory(IdObfuscator.toOpenId(1L), "GHOST", 1))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
    }

    @Test
    void setTypeMandatory_updates_and_echoes() {
        long tenantId = 88L;
        NfyaMessageType t = new NfyaMessageType();
        t.setTenantId(tenantId);
        t.setTypeCode("OTC");
        t.setMandatory(0);
        when(typeMapper.selectOne(any())).thenReturn(t);
        var data = newTypeService().setTypeMandatory(IdObfuscator.toOpenId(tenantId), "OTC", 1);
        assertThat(t.getMandatory()).isEqualTo(1);
        verify(typeMapper).updateById(t);
        assertThat(data).containsEntry("type_code", "OTC").containsEntry("mandatory", 1);
    }

    // ---- PlatformTenantService create / list / detail ----

    @Test
    void create_returns_open_id_and_once_secret() {
        when(tenantMapper.insert(any(NfyaTenant.class))).thenAnswer(inv -> {
            inv.<NfyaTenant>getArgument(0).setId(66L);
            return 1;
        });
        PostTenantsRequest req = postTenant();
        req.setPrivileges(Map.of("signature", true));
        var data = newTenantService().create(req);
        assertThat(IdObfuscator.fromOpenId((String) data.get("open_id"))).isEqualTo(66L); // 内部 id 不出网
        assertThat((String) data.get("tenant_secret")).hasSize(48).matches("[0-9a-f]+");
        verify(tenantMapper).update(any(), any()); // privileges 走 ::jsonb 参数化更新
    }

    @Test
    void create_duplicate_email_throws_10401() {
        when(tenantMapper.insert(any(NfyaTenant.class))).thenThrow(new DuplicateKeyException("uk"));
        assertThatThrownBy(() -> newTenantService().create(postTenant()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10401);
                    assertThat(e.getMessage()).isEqualTo("邮箱已被使用");
                });
    }

    @Test
    void create_without_optional_maps_skips_jsonb_update() {
        when(tenantMapper.insert(any(NfyaTenant.class))).thenAnswer(inv -> {
            inv.<NfyaTenant>getArgument(0).setId(1L);
            return 1;
        });
        newTenantService().create(postTenant());
        verify(tenantMapper, org.mockito.Mockito.never()).update(any(), any());
    }

    @Test
    void list_masks_email_addresses() {
        NfyaTenant t = tenant(5L, "ACTIVE");
        t.setCreatedAt(null);
        when(tenantMapper.selectList(any())).thenReturn(List.of(t));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) newTenantService().list().get("list");
        assertThat(list.get(0)).containsEntry("open_id", IdObfuscator.toOpenId(5L))
                .containsEntry("email", "o***s@example.com").containsEntry("status", "ACTIVE")
                .containsEntry("created_at", null);
    }

    @Test
    void detail_requires_valid_open_id() {
        assertThatThrownBy(() -> newTenantService().detail("@@bad"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
        when(tenantMapper.selectById(anyLong())).thenReturn(null);
        assertThatThrownBy(() -> newTenantService().detail(IdObfuscator.toOpenId(404L)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
    }

    @Test
    void detail_reads_jsonb_columns_as_text() {
        long id = 66L;
        when(tenantMapper.selectById(id)).thenReturn(tenant(id, "ACTIVE"));
        // privileges/config/oem 三次 selectMaps：有值 / 空集 / null
        when(tenantMapper.selectMaps(any())).thenReturn(
                List.of(Map.of("json_val", "{\"a\":1}")), List.of(), null);
        var data = newTenantService().detail(IdObfuscator.toOpenId(id));
        assertThat(data).containsEntry("open_id", IdObfuscator.toOpenId(id))
                .containsEntry("email", "o***s@example.com")
                .containsEntry("privileges", Map.of("a", 1))
                .containsEntry("config", Map.of())
                .containsEntry("oem", Map.of()); // 读盲区兜底：null → 空 Map
    }

    @Test
    void patch_updates_scalars_and_jsonb_columns() {
        long id = 66L;
        when(tenantMapper.selectById(id)).thenReturn(tenant(id, "ACTIVE")); // requireTenantId 属主存在性
        PatchTenantRequest req = new PatchTenantRequest();
        req.setName("新名");
        req.setDescription("新描述");
        req.setPrivileges(Map.of("delivery", 1));
        req.setOem(Map.of("theme", "dark"));
        var data = newTenantService().patch(IdObfuscator.toOpenId(id), req);
        assertThat(data).isEqualTo(Map.of("open_id", IdObfuscator.toOpenId(id)));
        verify(tenantMapper, org.mockito.Mockito.times(3)).update(any(), any()); // 标量 1 + jsonb 2
    }

    // ---- resetSecret ----

    @Test
    void reset_secret_rotates_and_moves_old_to_prev() {
        long id = 66L;
        when(tenantMapper.selectById(id)).thenReturn(tenant(id, "ACTIVE"));
        var data = newTenantService().resetSecret(IdObfuscator.toOpenId(id));
        ArgumentCaptor<NfyaTenant> cap = ArgumentCaptor.forClass(NfyaTenant.class);
        verify(tenantMapper).updateById(cap.capture());
        NfyaTenant saved = cap.getValue();
        assertThat(saved.getTenantSecret()).hasSize(48).isNotEqualTo("old-secret");
        assertThat(saved.getTenantSecretPrev()).isEqualTo("old-secret");
        assertThat(saved.getTenantSecretPrevAt()).isNotNull();
        assertThat(data).containsEntry("tenant_secret", saved.getTenantSecret());
    }

    @Test
    void reset_secret_with_blank_old_skips_prev_column() {
        long id = 66L;
        NfyaTenant t = tenant(id, "ACTIVE");
        t.setTenantSecret("");
        when(tenantMapper.selectById(id)).thenReturn(t);
        newTenantService().resetSecret(IdObfuscator.toOpenId(id));
        ArgumentCaptor<NfyaTenant> cap = ArgumentCaptor.forClass(NfyaTenant.class);
        verify(tenantMapper).updateById(cap.capture());
        assertThat(cap.getValue().getTenantSecretPrev()).isNull();
    }

    // ---- changeStatus 状态机 ----

    private void stubTenant(long id, String status) {
        when(tenantMapper.selectById(id)).thenReturn(tenant(id, status));
    }

    @Test
    void changeStatus_rejects_illegal_action() {
        assertThatThrownBy(() -> newTenantService().changeStatus(IdObfuscator.toOpenId(1L), "PAUSE"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10100));
        assertThatThrownBy(() -> newTenantService().changeStatus(IdObfuscator.toOpenId(1L), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10100));
    }

    @Test
    void suspend_active_tenant_updates_status_and_revokes_sessions() {
        stubTenant(66L, "ACTIVE");
        when(tenantMapper.update(any(), any())).thenReturn(1);
        var data = newTenantService().changeStatus(IdObfuscator.toOpenId(66L), "SUSPEND");
        assertThat(data).containsEntry("status", "SUSPENDED");
        verify(tenantMapper).update(any(), any()); // 精准 set status（不回写密钥列）
        verify(revoker).revoke(66L);
    }

    @Test
    void suspend_suspended_or_resume_active_or_close_closed_are_illegal_migrations() {
        stubTenant(66L, "SUSPENDED");
        assertThatThrownBy(() -> newTenantService().changeStatus(IdObfuscator.toOpenId(66L), "SUSPEND"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10402));

        stubTenant(66L, "ACTIVE");
        when(deliveryMapper.selectCount(any())).thenReturn(0L);
        assertThatThrownBy(() -> newTenantService().changeStatus(IdObfuscator.toOpenId(66L), "RESUME"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10402));

        stubTenant(66L, "CLOSED");
        assertThatThrownBy(() -> newTenantService().changeStatus(IdObfuscator.toOpenId(66L), "CLOSE"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10402));
    }

    @Test
    void resume_suspended_tenant_returns_active_without_revoking() {
        stubTenant(66L, "SUSPENDED");
        when(tenantMapper.update(any(), any())).thenReturn(1);
        var data = newTenantService().changeStatus(IdObfuscator.toOpenId(66L), "RESUME");
        assertThat(data).containsEntry("status", "ACTIVE");
        verify(revoker, org.mockito.Mockito.never()).revoke(anyLong()); // RESUME 不撤销
    }

    @Test
    void close_blocked_by_pending_or_sending_deliveries() {
        stubTenant(66L, "ACTIVE");
        when(deliveryMapper.selectCount(any())).thenReturn(3L); // PENDING/SENDING 存量
        assertThatThrownBy(() -> newTenantService().changeStatus(IdObfuscator.toOpenId(66L), "CLOSE"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10402);
                    assertThat(e.getMessage()).contains("3");
                });
        verify(tenantMapper, org.mockito.Mockito.never()).update(any(), any());
    }

    @Test
    void close_without_pending_deliveries_closes_and_revokes() {
        stubTenant(66L, "ACTIVE");
        when(deliveryMapper.selectCount(any())).thenReturn(0L);
        when(tenantMapper.update(any(), any())).thenReturn(1);
        var data = newTenantService().changeStatus(IdObfuscator.toOpenId(66L), "CLOSE");
        assertThat(data).containsEntry("status", "CLOSED");
        verify(revoker).revoke(66L);
    }

    @Test
    void revoke_failure_does_not_block_state_change() {
        stubTenant(66L, "ACTIVE");
        when(tenantMapper.update(any(), any())).thenReturn(1);
        doThrow(new RuntimeException("redis down")).when(revoker).revoke(66L);
        var data = newTenantService().changeStatus(IdObfuscator.toOpenId(66L), "SUSPEND"); // 留痕不阻断
        assertThat(data).containsEntry("status", "SUSPENDED");
    }

    @Test
    void missing_revoker_bean_is_tolerated() {
        when(revokerProvider.getIfAvailable()).thenReturn(null); // 无 Redis 上下文
        stubTenant(66L, "ACTIVE");
        when(tenantMapper.update(any(), any())).thenReturn(1);
        var data = new PlatformTenantService(tenantMapper, deliveryMapper, new ObjectMapper(), revokerProvider)
                .changeStatus(IdObfuscator.toOpenId(66L), "SUSPEND");
        assertThat(data).containsEntry("status", "SUSPENDED");
    }

    // ---- maskEmail（静态，TENANT 面列表/详情共用）----

    @Test
    void mask_email_rules() {
        assertThat(PlatformTenantService.maskEmail("justin@example.com")).isEqualTo("j***n@example.com");
        assertThat(PlatformTenantService.maskEmail("a@example.com")).isEqualTo("a***@example.com");
        assertThat(PlatformTenantService.maskEmail("no-at-sign")).isEqualTo("***");
        assertThat(PlatformTenantService.maskEmail("")).isNull();
        assertThat(PlatformTenantService.maskEmail(null)).isNull();
    }
}
