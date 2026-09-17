package fun.commons.notification4j.controller;

import fun.commons.framework4j.web.ApiResponse;
import fun.commons.notification4j.dto.OpenRegisterRequest;
import fun.commons.notification4j.service.RegistrationKeyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * API-OPEN-001 凭注册码自助注册租户（§4.1 开放域；无鉴权，IP 限流兜底）。
 * 注册路径双通道：异基包走 autoconfig（enable-api 开关），同基包走组件扫描（沿 benefit4j 惯例）。
 */
@RestController
@RequestMapping("/nfy/open/api/v1/tenants")
@RequiredArgsConstructor
public class NfyOpenRegisterController {

    private final RegistrationKeyService registrationKeyService;

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@Valid @RequestBody OpenRegisterRequest req) {
        return ApiResponse.success(registrationKeyService.register(req.registrationKey(), req.name()));
    }
}
