package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.TenantDomain;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.notification4j.dto.PatchTypesRequest;
import fun.commons.notification4j.dto.PostTypesRequest;
import fun.commons.notification4j.entity.NfyaMessageType;
import fun.commons.notification4j.service.MessageTypeService;
import fun.commons.notification4j.util.NfyTenantContexts;
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
 * API-TYP-001/002 类型管理（租户域 admin 面；TENANT token）。
 * mandatory 仅平台域可设（PTE-005），本控制器不暴露该字段。
 */
@TenantDomain
@RequiresToken(value = "TENANT", type = "access")
@RestController
// 注册路径: ① 异基包嵌入方 → autoconfig @Bean(enable-api 开关真实生效) ② 同基包/独立部署 → 组件扫描(开关不拦截, 沿 benefit4j 惯例)
@RequestMapping("/nfy/api/v1/admin/types")
@RequiredArgsConstructor
public class NfyAdminTypeController {

    private final MessageTypeService messageTypeService;

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody PostTypesRequest req) {
        NfyaMessageType t = messageTypeService.create(NfyTenantContexts.tenantId(), req);
        return ApiResponse.success(Map.of("type_id", String.valueOf(t.getId())));
    }

    @GetMapping
    public ApiResponse<java.util.List<Map<String, Object>>> list() {
        return ApiResponse.success(messageTypeService.toVoList(messageTypeService.list(NfyTenantContexts.tenantId())));
    }

    @PatchMapping("/{type_id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable("type_id") long typeId,
                                                   @Valid @RequestBody PatchTypesRequest req) {
        NfyaMessageType t = messageTypeService.update(NfyTenantContexts.tenantId(), typeId, req);
        return ApiResponse.success(Map.of(
                "type_id", String.valueOf(t.getId()),
                "status", t.getStatus()));
    }
}
