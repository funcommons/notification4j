package fun.commons.notification4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** API-TYP-001 创建类型（mandatory 仅平台域可设，租户域接口不暴露） */
public record PostTypesRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_]{1,32}$", message = "类型编码格式不正确") String typeCode,
        @NotBlank String name,
        String description,
        String defaultLevel,
        java.util.List<String> defaultChannels) {
}
