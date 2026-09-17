package fun.commons.notification4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * API-CHN-002 注册渠道（§5.7）。
 * channel_type 枚举 / target SSRF 白名单为业务校验（service 层 10609/10100），
 * 本注解层承载必填与长度（fwk4j-web 统一 10100）。
 */
public record PostChannelsRequest(
        @NotBlank String channelType,
        @NotBlank @Size(min = 1, max = 30) String name,
        @NotBlank @Size(max = 516) String target,
        @Size(max = 128) String secret,
        @Size(max = 20) String keyword) {
}
