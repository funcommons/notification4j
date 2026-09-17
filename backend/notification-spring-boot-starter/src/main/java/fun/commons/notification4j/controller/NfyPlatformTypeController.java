package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.PlatformDomain;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.notification4j.service.PlatformTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * API-PTE-005 设置类型强制订阅（§4.4 平台域；PLATFORM token）。
 * mandatory 唯一设置入口：租户域 admin 面提交该字段即忽略（§5.9.2 PTE-005）。
 */
// 平台域口径（第 8a 步 IT 实测钉死）：PLATFORM client 经 TenantAuthEndpoint 换取的是
// 「tenant_id=0 的 TENANT 型别合成 token」——@RequiresToken(TENANT) 负责 token 校验+claims 回填，
// @PlatformDomain 域闸校验 tenant_id==0（租户 token tenant_id>0 → 403 拒）。
@PlatformDomain
@RequiresToken(value = "TENANT", type = "access")
@RestController
@RequestMapping("/nfy/platform/api/v1/tenants/{tenant_open_id}/type-mandatory")
@RequiredArgsConstructor
public class NfyPlatformTypeController {

    private final PlatformTypeService platformTypeService;

    /** body: {type_code, mandatory: 0|1} */
    @PostMapping
    public ApiResponse<Map<String, Object>> setTypeMandatory(@PathVariable("tenant_open_id") String tenantOpenId,
                                                             @RequestBody Map<String, Object> body) {
        String typeCode = body.get("type_code") == null ? "" : String.valueOf(body.get("type_code"));
        Integer mandatory = body.get("mandatory") == null ? null
                : Integer.valueOf(String.valueOf(body.get("mandatory")));
        return ApiResponse.success(platformTypeService.setTypeMandatory(tenantOpenId, typeCode, mandatory));
    }
}
