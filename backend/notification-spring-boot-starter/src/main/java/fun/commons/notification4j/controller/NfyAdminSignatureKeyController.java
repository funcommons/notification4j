package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.TenantDomain;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.notification4j.dto.PostSignatureKeyRequest;
import fun.commons.notification4j.service.SignatureKeyService;
import fun.commons.notification4j.util.NfyTenantContexts;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * API-SEC-001 签名密钥查询（脱敏）/注册/轮换（§4.3 admin 面；TENANT token）。
 * 注册路径双通道：异基包走 autoconfig（enable-api 开关），同基包走组件扫描（沿 benefit4j 惯例）。
 */
@TenantDomain
@RequiresToken(value = "TENANT", type = "access")
@RestController
@RequestMapping("/nfy/api/v1/admin/signature-key")
@RequiredArgsConstructor
public class NfyAdminSignatureKeyController {

    private final SignatureKeyService signatureKeyService;

    @GetMapping
    public ApiResponse<Map<String, Object>> getStatus() {
        return ApiResponse.success(signatureKeyService.getStatus(NfyTenantContexts.tenantId()));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> rotate(@Valid @RequestBody PostSignatureKeyRequest req) {
        return ApiResponse.success(signatureKeyService.rotate(NfyTenantContexts.tenantId(), req.secret()));
    }
}
