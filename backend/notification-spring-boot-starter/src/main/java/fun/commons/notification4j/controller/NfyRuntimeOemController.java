package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.TenantDomain;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.notification4j.service.OemService;
import fun.commons.notification4j.util.NfyTenantContexts;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * API-OEM-001 本租户 oem.hosts 下发（runtime 面，V1.3；GitHub issue #1 补实现）。
 * <p>
 * 嵌入消息中心握手时，构建时白名单（VITE_NFY_PARENT_ORIGINS）外的父页 origin
 * 由 iframe 持 token 调本端点核验：origin ∈ oem.hosts 才接受 NFY_TOKEN。
 * T 鉴权无 U（同 DICT-001 口径——下发的是租户配置而非用户数据）。
 * 注册路径双通道：异基包走 autoconfig（enable-api 开关），同基包走组件扫描。
 */
@TenantDomain
@RequiresToken(value = "TENANT", type = "access")
@RestController
@RequestMapping("/nfy/api/v1/runtime/oem")
@RequiredArgsConstructor
public class NfyRuntimeOemController {

    private final OemService oemService;

    /** OEM-001 本租户 oem.hosts（origin 白名单运行时面；无 hosts 配置回 []） */
    @GetMapping("/hosts")
    public ApiResponse<Map<String, Object>> hosts() {
        return ApiResponse.success(oemService.hosts(NfyTenantContexts.tenantId()));
    }
}
