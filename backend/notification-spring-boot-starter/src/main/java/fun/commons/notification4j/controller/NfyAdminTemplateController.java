package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.TenantDomain;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.notification4j.service.TemplateService;
import fun.commons.notification4j.util.NfyTenantContexts;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * API-TPL-001/002/003 模板域（§5.9.2 admin 面；V1.1 契约提前落地）。
 * 注册路径双通道：异基包走 autoconfig（enable-api 开关），同基包走组件扫描（沿 benefit4j 惯例）。
 */
@TenantDomain
@RequiresToken(value = "TENANT", type = "access")
@RestController
@RequestMapping("/nfy/api/v1/admin/templates")
@RequiredArgsConstructor
public class NfyAdminTemplateController {

    private final TemplateService templateService;

    /** TPL-001 列表 */
    @GetMapping
    public ApiResponse<Map<String, Object>> list() {
        return ApiResponse.success(templateService.list(NfyTenantContexts.tenantId()));
    }

    /** TPL-001 创建 */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> req) {
        return ApiResponse.success(templateService.create(NfyTenantContexts.tenantId(), req));
    }

    /** TPL-002 详情 */
    @GetMapping("/{template_id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("template_id") String templateId) {
        return ApiResponse.success(templateService.detail(NfyTenantContexts.tenantId(), templateId));
    }

    /** TPL-002 修改 */
    @PatchMapping("/{template_id}")
    public ApiResponse<Map<String, Object>> patch(@PathVariable("template_id") String templateId,
                                                  @RequestBody Map<String, Object> req) {
        return ApiResponse.success(templateService.patch(NfyTenantContexts.tenantId(), templateId, req));
    }

    /** TPL-002 删除（逻辑删，删后同 code 可重建） */
    @DeleteMapping("/{template_id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable("template_id") String templateId) {
        return ApiResponse.success(templateService.delete(NfyTenantContexts.tenantId(), templateId));
    }

    /** TPL-003 渲染预览（10603 参数缺失） */
    @PostMapping("/preview")
    public ApiResponse<Map<String, Object>> preview(@RequestBody Map<String, Object> req) {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) req.get("params");
        return ApiResponse.success(templateService.preview(
                NfyTenantContexts.tenantId(),
                String.valueOf(req.get("template_code")),
                String.valueOf(req.get("type_code")),
                params));
    }
}
