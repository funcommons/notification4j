package fun.commons.notification4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** API-OPEN-001 凭注册码���助注册（开放域无鉴权；IP 限流兜底） */
public record OpenRegisterRequest(
        @NotBlank(message = "registration_key不能为空") String registrationKey,
        @NotBlank(message = "name不能为空")
        @Size(max = 64, message = "name最长64字符") String name) {
}
