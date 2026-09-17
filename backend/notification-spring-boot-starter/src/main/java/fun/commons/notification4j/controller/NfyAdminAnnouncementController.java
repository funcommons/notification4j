package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.TenantDomain;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.notification4j.dto.PatchAnnouncementsAnnouncementIdRequest;
import fun.commons.notification4j.dto.PostAnnouncementsRequest;
import fun.commons.notification4j.service.AnnouncementAdminService;
import fun.commons.notification4j.util.NfyTenantContexts;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * API-AAN-001~005 租户公告管理面（TENANT admin 面）。
 * 注册路径双通道：异基包走 autoconfig（enable-api 开关），同基包走组件扫描（沿 benefit4j 惯例）。
 */
@TenantDomain
@RequiresToken(value = "TENANT", type = "access")
@RestController
@RequestMapping("/nfy/api/v1/admin/announcements")
@RequiredArgsConstructor
public class NfyAdminAnnouncementController {

    private final AnnouncementAdminService announcementAdminService;

    /** API-AAN-001 列表（status 过滤 + Offset 分页） */
    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) Integer offset,
                                                 @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(announcementAdminService.list(
                NfyTenantContexts.tenantId(), status, offset, limit));
    }

    /** API-AAN-001 创建（→ DRAFT） */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody PostAnnouncementsRequest req) {
        return ApiResponse.success(announcementAdminService.create(NfyTenantContexts.tenantId(), req));
    }

    /** API-AAN-002 详情（全字段） */
    @GetMapping("/{announcement_id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("announcement_id") String announcementId) {
        return ApiResponse.success(announcementAdminService.detail(
                NfyTenantContexts.tenantId(), announcementId));
    }

    /** API-AAN-002 修改（限 DRAFT） */
    @PatchMapping("/{announcement_id}")
    public ApiResponse<Map<String, Object>> patch(@PathVariable("announcement_id") String announcementId,
                                                  @Valid @RequestBody PatchAnnouncementsAnnouncementIdRequest req) {
        return ApiResponse.success(announcementAdminService.patch(
                NfyTenantContexts.tenantId(), announcementId, req));
    }

    /** API-AAN-003 发布 */
    @PostMapping("/{announcement_id}/publish")
    public ApiResponse<Map<String, Object>> publish(@PathVariable("announcement_id") String announcementId) {
        return ApiResponse.success(announcementAdminService.publish(
                NfyTenantContexts.tenantId(), announcementId));
    }

    /** API-AAN-004 下线 */
    @PostMapping("/{announcement_id}/offline")
    public ApiResponse<Map<String, Object>> offline(@PathVariable("announcement_id") String announcementId) {
        return ApiResponse.success(announcementAdminService.offline(
                NfyTenantContexts.tenantId(), announcementId));
    }

    /** API-AAN-005 统计（confirms Offset 分页） */
    @GetMapping("/{announcement_id}/stats")
    public ApiResponse<Map<String, Object>> stats(@PathVariable("announcement_id") String announcementId,
                                                  @RequestParam(required = false) Integer offset,
                                                  @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(announcementAdminService.stats(
                NfyTenantContexts.tenantId(), announcementId, offset, limit));
    }
}
