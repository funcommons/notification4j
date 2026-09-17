package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.TenantDomain;
import fun.commons.notification4j.util.NfyTenantContexts;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.notification4j.service.AnnouncementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * API-ANN-001/002/003 公告 runtime 面（TENANT token + X-User-Id）。
 * 注册路径双通道：异基包走 autoconfig（enable-api 开关），同基包走组件扫描（沿 benefit4j 惯例）。
 */
@TenantDomain
@RequiresToken(value = "TENANT", type = "access")
@RestController
@RequestMapping("/nfy/api/v1/runtime/announcements")
@RequiredArgsConstructor
public class NfyRuntimeAnnouncementController {

    private final AnnouncementService announcementService;

    /** API-ANN-001 生效公告列表（平台 + 本租户，Cursor 分页） */
    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String cursor,
                                                 @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(announcementService.listEffective(
                tenantId(), NfyTenantContexts.userId(), cursor, limit));
    }

    /** API-ANN-002 标记阅读（幂等） */
    @PostMapping("/{announcement_id}/read")
    public ApiResponse<Map<String, Object>> markRead(@PathVariable("announcement_id") String announcementId) {
        return ApiResponse.success(announcementService.markRead(
                tenantId(), NfyTenantContexts.userId(), announcementId));
    }

    /** API-ANN-003 确认「我知道了」（幂等） */
    @PostMapping("/{announcement_id}/confirm")
    public ApiResponse<Map<String, Object>> confirm(@PathVariable("announcement_id") String announcementId) {
        return ApiResponse.success(announcementService.confirm(
                tenantId(), NfyTenantContexts.userId(), announcementId));
    }

    private static long tenantId() {
        return NfyTenantContexts.tenantId();
    }
}
