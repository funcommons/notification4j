package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.PlatformDomain;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.notification4j.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** API-PST-001 平台跨租户概览（§4.4 平台域；合成 token 口径） */
@PlatformDomain
@RequiresToken(value = "TENANT", type = "access")
@RestController
@RequestMapping("/nfy/platform/api/v1/stats")
@RequiredArgsConstructor
public class NfyPlatformStatsController {

    private final StatsService statsService;

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.success(statsService.platformOverview());
    }
}
