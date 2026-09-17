package fun.commons.notification4j.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** API-TYP-002 修改类型（部分更新；status 仅 ENABLED/DISABLED） */
public record PatchTypesRequest(
        @Size(min = 1, max = 68) String name,
        @Size(max = 259) String description,
        @Pattern(regexp = "NORMAL|IMPORTANT|URGENT") String defaultLevel,
        java.util.List<String> defaultChannels,
        @Pattern(regexp = "ENABLED|DISABLED") String status) {
}
