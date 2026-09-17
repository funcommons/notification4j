package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.TenantDomain;
import fun.commons.notification4j.util.NfyTenantContexts;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.notification4j.dto.PutSubscriptionsRequest;
import fun.commons.notification4j.service.SubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * API-SUB-001/002 订阅矩阵（租户域 runtime 面；TENANT token + X-User-Id）。
 * 注册路径双通道：异基包走 autoconfig（enable-api 开关），同基包走组件扫描（沿 benefit4j 惯例）。
 */
@TenantDomain
@RequiresToken(value = "TENANT", type = "access")
@RestController
@RequestMapping("/nfy/api/v1/runtime/subscriptions")
@RequiredArgsConstructor
public class NfyRuntimeSubscriptionController {

    private final SubscriptionService subscriptionService;

    @GetMapping
    public ApiResponse<Map<String, Object>> get() {
        return ApiResponse.success(subscriptionService.get(tenantId(), NfyTenantContexts.userId()));
    }

    @PutMapping
    public ApiResponse<Map<String, Object>> save(@Valid @RequestBody PutSubscriptionsRequest req) {
        return ApiResponse.success(subscriptionService.save(tenantId(), NfyTenantContexts.userId(), req));
    }

    private static long tenantId() {
        return NfyTenantContexts.tenantId();
    }
}
