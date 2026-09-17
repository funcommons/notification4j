package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.PlatformDomain;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.notification4j.dto.PostRegistrationKeysRequest;
import fun.commons.notification4j.service.RegistrationKeyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** API-PRK-001 注册码列表/签发（§4.4 平台域；码一次性显示，列表仅脱敏） */
@PlatformDomain
@RequiresToken(value = "TENANT", type = "access")
@RestController
@RequestMapping("/nfy/platform/api/v1/registration-keys")
@RequiredArgsConstructor
public class NfyPlatformRegistrationKeyController {

    private final RegistrationKeyService registrationKeyService;

    @GetMapping
    public ApiResponse<Map<String, Object>> list() {
        return ApiResponse.success(registrationKeyService.list());
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> issue(@Valid @RequestBody PostRegistrationKeysRequest req) {
        return ApiResponse.success(registrationKeyService.issue(
                req.maxUses() == null ? null : req.maxUses().longValue(),
                req.expireHours() == null ? null : req.expireHours().longValue(),
                req.preset(), "PLATFORM"));
    }
}
