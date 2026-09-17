package fun.commons.notification4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** API-SEC-001 注册/轮换签名密钥：明文 16~71（71 为加密后适配 varchar(132) 的上限，评审 P1） */
public record PostSignatureKeyRequest(
        @NotBlank @Size(min = 16, max = 71) String secret) {
}
