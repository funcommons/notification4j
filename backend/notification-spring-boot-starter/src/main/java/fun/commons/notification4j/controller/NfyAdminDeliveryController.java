package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.TenantDomain;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.notification4j.service.DeliveryAdminService;
import fun.commons.notification4j.util.NfyTenantContexts;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * API-DLV-001/002 投递记录查询/人工重投（TENANT admin 面）。
 * 注册路径双通道：异基包走 autoconfig（enable-api 开关），同基包走组件扫描（沿 benefit4j 惯例）。
 */
@TenantDomain
@RequiresToken(value = "TENANT", type = "access")
@RestController
@RequestMapping("/nfy/api/v1/admin/deliveries")
@RequiredArgsConstructor
public class NfyAdminDeliveryController {

    private final DeliveryAdminService deliveryAdminService;

    /** API-DLV-001 投递记录查询（biz_no/userid/channel_type/status/时间窗 + Offset） */
    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String biz_no,
                                                 @RequestParam(required = false) String userid,
                                                 @RequestParam(required = false) String channel_type,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) Long created_after,
                                                 @RequestParam(required = false) Long created_before,
                                                 @RequestParam(required = false) Integer offset,
                                                 @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(deliveryAdminService.list(NfyTenantContexts.tenantId(),
                biz_no, userid, channel_type, status, created_after, created_before, offset, limit));
    }

    /** API-DLV-002 人工重投（仅 DEAD） */
    @PostMapping("/{delivery_id}/retry")
    public ApiResponse<Map<String, Object>> retry(@PathVariable("delivery_id") String deliveryId) {
        return ApiResponse.success(deliveryAdminService.retry(NfyTenantContexts.tenantId(), deliveryId));
    }
}
