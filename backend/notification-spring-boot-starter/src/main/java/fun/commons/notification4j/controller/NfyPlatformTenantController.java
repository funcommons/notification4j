package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.PlatformDomain;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.notification4j.dto.PatchTenantRequest;
import fun.commons.notification4j.dto.PostTenantsRequest;
import fun.commons.notification4j.service.PlatformTenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * API-PTE-001~004 租户生命周期（§4.4 平台域；@PlatformDomain+@RequiresToken(TENANT) 合成口径）。
 * 路径参数一律 tenant_open_id（内部雪花 id 不出网）。
 */
@PlatformDomain
@RequiresToken(value = "TENANT", type = "access")
@RestController
@RequestMapping("/nfy/platform/api/v1/tenants")
@RequiredArgsConstructor
public class NfyPlatformTenantController {

    private final PlatformTenantService platformTenantService;

    /** PTE-001 列表 */
    @GetMapping
    public ApiResponse<Map<String, Object>> list() {
        return ApiResponse.success(platformTenantService.list());
    }

    /** PTE-001 创建（明文密钥仅此一次返回） */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody PostTenantsRequest req) {
        return ApiResponse.success(platformTenantService.create(req));
    }

    /** PTE-002 详情（email 脱敏 + 三组配置） */
    @GetMapping("/{tenant_open_id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("tenant_open_id") String tenantOpenId) {
        return ApiResponse.success(platformTenantService.detail(tenantOpenId));
    }

    /** PTE-002 配置修改（privileges/config/oem + name/description） */
    @PatchMapping("/{tenant_open_id}")
    public ApiResponse<Map<String, Object>> patch(@PathVariable("tenant_open_id") String tenantOpenId,
                                                  @Valid @RequestBody PatchTenantRequest req) {
        return ApiResponse.success(platformTenantService.patch(tenantOpenId, req));
    }

    /** PTE-003 重置密钥（新明文一次；旧密钥进 prev 宽限） */
    @PostMapping("/{tenant_open_id}/reset-secret")
    public ApiResponse<Map<String, Object>> resetSecret(@PathVariable("tenant_open_id") String tenantOpenId) {
        return ApiResponse.success(platformTenantService.resetSecret(tenantOpenId));
    }

    /** PTE-004 状态机（SUSPEND/RESUME/CLOSE） */
    @PostMapping("/{tenant_open_id}/status")
    public ApiResponse<Map<String, Object>> status(@PathVariable("tenant_open_id") String tenantOpenId,
                                                   @RequestBody Map<String, String> body) {
        return ApiResponse.success(platformTenantService.changeStatus(
                tenantOpenId, body.get("action")));
    }
}
