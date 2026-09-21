package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.PlatformDomain;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.notification4j.service.PlatformMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * API-PPM-001/002 平台站内信查询（§4.4 平台域；PLATFORM token，tenant_id=0 合成租户）。
 * 与 PAN 同构：@PlatformDomain 域闸（租户 token tenant_id>0 → 403 拒）+ @RequiresToken(TENANT)；
 * 查询跨租户全量（含系统消息如作品失败通知），只读面——发送仍走 runtime MSG-001。
 * 注册路径双通道：异基包走 autoconfig（enable-api 开关），同基包走组件扫描（沿 benefit4j 惯例）。
 */
// 平台域口径（第 8a 步 IT 实测钉死）：PLATFORM client 经 TenantAuthEndpoint 换取的是
// 「tenant_id=0 的 TENANT 型别合成 token」——@RequiresToken(TENANT) 负责 token 校验+claims 回填，
// @PlatformDomain 域闸校验 tenant_id==0（租户 token tenant_id>0 → 403 拒）。
@PlatformDomain
@RequiresToken(value = "TENANT", type = "access")
@RestController
@RequestMapping("/nfy/platform/api/v1/messages")
@RequiredArgsConstructor
public class NfyPlatformMessageController {

    private final PlatformMessageService platformMessageService;

    /** API-PPM-001 全量消息列表（跨租户；user_id/type_code/时间范围/keyword 可选筛选 + offset/limit） */
    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(name = "user_id", required = false) String userId,
                                                 @RequestParam(name = "type_code", required = false) String typeCode,
                                                 @RequestParam(name = "created_from", required = false) Long createdFrom,
                                                 @RequestParam(name = "created_to", required = false) Long createdTo,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) Integer offset,
                                                 @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(platformMessageService.list(
                userId, typeCode, createdFrom, createdTo, keyword, offset, limit));
    }

    /** API-PPM-002 消息详情（全字段 + 已读回执统计） */
    @GetMapping("/{message_id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("message_id") String messageId) {
        return ApiResponse.success(platformMessageService.detail(messageId));
    }
}
