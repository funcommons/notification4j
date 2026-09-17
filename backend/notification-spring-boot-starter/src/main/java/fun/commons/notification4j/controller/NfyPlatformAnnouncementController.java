package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.PlatformDomain;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.notification4j.dto.PatchAnnouncementsAnnouncementIdRequest;
import fun.commons.notification4j.dto.PostAnnouncementsRequest;
import fun.commons.notification4j.service.AnnouncementAdminService;
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

import java.util.List;
import java.util.Map;

/**
 * API-PAN-001~004 平台公告（§4.4 平台域；PLATFORM token，tenant_id=0 合成租户）。
 * 与 AAN 同构：复用 AnnouncementAdminService（tenant 0 口径）；channel_ids 恒空（平台域无渠道资源）。
 * 注册路径双通道：异基包走 autoconfig（enable-api 开关），同基包走组件扫描（沿 benefit4j 惯例）。
 */
// 平台域口径（第 8a 步 IT 实测钉死）：PLATFORM client 经 TenantAuthEndpoint 换取的是
// 「tenant_id=0 的 TENANT 型别合成 token」——@RequiresToken(TENANT) 负责 token 校验+claims 回填，
// @PlatformDomain 域闸校验 tenant_id==0（租户 token tenant_id>0 → 403 拒）。
@PlatformDomain
@RequiresToken(value = "TENANT", type = "access")
@RestController
@RequestMapping("/nfy/platform/api/v1/announcements")
@RequiredArgsConstructor
public class NfyPlatformAnnouncementController {

    private final AnnouncementAdminService announcementAdminService;

    /** API-PAN-001 列表（tenant 0，status 过滤 + Offset） */
    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) Integer offset,
                                                 @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(announcementAdminService.list(0L, status, offset, limit));
    }

    /** API-PAN-001 创建（scope=PLATFORM，channel_ids 强制空） */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody PostAnnouncementsRequest req) {
        return ApiResponse.success(announcementAdminService.createPlatform(req));
    }

    /** API-PAN-002 详情 */
    @GetMapping("/{announcement_id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("announcement_id") String announcementId) {
        return ApiResponse.success(announcementAdminService.detail(0L, announcementId));
    }

    /** API-PAN-002 修改（限 DRAFT；channel_ids 忽略提交值，恒清空） */
    @PatchMapping("/{announcement_id}")
    public ApiResponse<Map<String, Object>> patch(@PathVariable("announcement_id") String announcementId,
                                                  @Valid @RequestBody PatchAnnouncementsAnnouncementIdRequest req) {
        PatchAnnouncementsAnnouncementIdRequest sanitized = new PatchAnnouncementsAnnouncementIdRequest(
                req.title(), req.content(), req.level(), req.effectiveAt(), req.expireAt(),
                req.needConfirm(), req.linkUrl(), List.of());
        return ApiResponse.success(announcementAdminService.patch(0L, announcementId, sanitized));
    }

    /** API-PAN-004 发布（外发=订阅 ANNOUNCEMENT 用户的站外渠道） */
    @PostMapping("/{announcement_id}/publish")
    public ApiResponse<Map<String, Object>> publish(@PathVariable("announcement_id") String announcementId) {
        return ApiResponse.success(announcementAdminService.publish(0L, announcementId));
    }

    /** API-PAN-003 下线 */
    @PostMapping("/{announcement_id}/offline")
    public ApiResponse<Map<String, Object>> offline(@PathVariable("announcement_id") String announcementId) {
        return ApiResponse.success(announcementAdminService.offline(0L, announcementId));
    }
}
