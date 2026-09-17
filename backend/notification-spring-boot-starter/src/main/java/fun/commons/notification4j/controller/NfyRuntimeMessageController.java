package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.TenantDomain;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.notification4j.dto.PostMessagesRequest;
import fun.commons.notification4j.dto.PostReadRequest;
import fun.commons.notification4j.service.AnnouncementService;
import fun.commons.notification4j.service.MessageService;
import fun.commons.notification4j.util.NfyTenantContexts;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API-MSG-001/004/006/007/009 消息发送与站内信（租户域 runtime 面；TENANT token + X-User-Id）。
 * 字面量子路径（unread-count/read）与后续 {message_id} 路由共存：message_id 恒为雪花数字串（§3 URL 设计说明）。
 */
@TenantDomain
@RequiresToken(value = "TENANT", type = "access")
@RestController
// 注册路径: ① 异基包嵌入方 → autoconfig @Bean(enable-api 开关真实生效) ② 同基包/独立部署 → 组件扫描(开关不拦截, 沿 benefit4j 惯例)
@RequestMapping("/nfy/api/v1/runtime/messages")
@RequiredArgsConstructor
public class NfyRuntimeMessageController {

    private final MessageService messageService;
    private final AnnouncementService announcementService;
    private final fun.commons.notification4j.service.BatchJobService batchJobService;

    @PostMapping
    public ApiResponse<Map<String, Object>> send(@Valid @RequestBody PostMessagesRequest req) {
        return ApiResponse.success(messageService.send(NfyTenantContexts.tenantId(), req));
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String type_code,
                                                 @RequestParam(required = false) String level,
                                                 @RequestParam(required = false) String read_status,
                                                 @RequestParam(required = false) String cursor,
                                                 @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(messageService.list(
                NfyTenantContexts.tenantId(), NfyTenantContexts.userId(),
                type_code, level, read_status, cursor, limit));
    }

    /** 未读数 = 站内信未读 + 公告未确认（need_confirm=1 生效公告减已确认回执，§5.3） */
    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Object>> unreadCount() {
        long tenantId = NfyTenantContexts.tenantId();
        String userid = NfyTenantContexts.userId();
        long inapp = messageService.unreadInappCount(tenantId, userid);
        long unconfirmed = announcementService.unconfirmedCount(tenantId, userid);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("unread_count", inapp);
        data.put("unconfirmed_count", unconfirmed);
        data.put("total", inapp + unconfirmed);
        return ApiResponse.success(data);
    }

    @PostMapping("/read")
    public ApiResponse<Map<String, Object>> markRead(@Valid @RequestBody PostReadRequest req) {
        return ApiResponse.success(messageService.markRead(
                NfyTenantContexts.tenantId(), NfyTenantContexts.userId(),
                req.messageIds(), req.all()));
    }

    /** MSG-008 最近 N 条（铃铛下拉；字面量路径与 {message_id} 共存，message_id 恒数字串） */
    @GetMapping("/recent")
    public ApiResponse<Map<String, Object>> recent(@RequestParam(required = false) Integer limit) {
        return ApiResponse.success(messageService.recent(
                NfyTenantContexts.tenantId(), NfyTenantContexts.userId(), limit));
    }

    /** MSG-005 消息详情（返回即置已读） */
    @GetMapping("/{message_id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("message_id") String messageId) {
        return ApiResponse.success(messageService.detailAndMarkRead(
                NfyTenantContexts.tenantId(), NfyTenantContexts.userId(), messageId));
    }

    /** MSG-009 消息撤回（V1.2）：T 鉴权发送方行为；SENT→CANCELLED + 级联拦截 PENDING 投递；已撤回幂等成功 */
    @PostMapping("/{message_id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable("message_id") String messageId) {
        return ApiResponse.success(messageService.cancel(NfyTenantContexts.tenantId(), messageId));
    }

    /** MSG-002 批量发送（V1.1 提前落地）：立即返回 job_id，后台异步执行 */
    @PostMapping("/batch")
    public ApiResponse<Map<String, Object>> batchSend(@Valid @RequestBody PostMessagesRequest req) {
        return ApiResponse.success(batchJobService.submit(NfyTenantContexts.tenantId(), req));
    }
}
