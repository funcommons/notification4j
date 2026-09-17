package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.TenantDomain;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.notification4j.service.StatsService;
import fun.commons.notification4j.util.NfyTenantContexts;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** API-STAT-001 租户概览（§4.3 admin 面） */
@TenantDomain
@RequiresToken(value = "TENANT", type = "access")
@RestController
@RequestMapping("/nfy/api/v1/admin/stats")
@RequiredArgsConstructor
public class NfyAdminStatsController {

    private final StatsService statsService;

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.success(statsService.tenantOverview(NfyTenantContexts.tenantId()));
    }
}
