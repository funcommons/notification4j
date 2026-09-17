package fun.commons.notification4j.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** API-AAN-002 修改公告（部分更新；仅 DRAFT 态可改，service 层 10402；content 传空串非法） */
public record PatchAnnouncementsAnnouncementIdRequest(
        @Size(min = 1, max = 132) String title,
        @Size(min = 1, max = 20000) String content,
        @Pattern(regexp = "NORMAL|IMPORTANT|URGENT") String level,
        Long effectiveAt,
        Long expireAt,
        Integer needConfirm,
        @Size(max = 516) String linkUrl,
        @Size(max = 20) List<@Size(max = 20) String> channelIds) {
}
