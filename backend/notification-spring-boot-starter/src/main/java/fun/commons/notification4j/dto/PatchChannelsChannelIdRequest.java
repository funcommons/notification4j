package fun.commons.notification4j.dto;

import jakarta.validation.constraints.Size;

/** API-CHN-004 改名/启停（§5.9.1）：status 仅 ENABLED/DISABLED；ENABLED 须已验证（10610） */
public record PatchChannelsChannelIdRequest(
        @Size(min = 1, max = 30) String name,
        String status) {
}
