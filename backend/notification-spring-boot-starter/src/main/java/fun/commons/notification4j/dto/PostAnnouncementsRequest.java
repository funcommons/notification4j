package fun.commons.notification4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** API-AAN-001 创建租户公告（§5.9.2）：→ DRAFT；时间窗 epoch 毫秒；channel_ids=公共渠道 id（发布时外发） */
public record PostAnnouncementsRequest(
        @NotBlank @Size(max = 132) String title,
        @NotBlank @Size(max = 20000) String content,
        @Pattern(regexp = "NORMAL|IMPORTANT|URGENT") String level,
        @NotNull Long effectiveAt,
        @NotNull Long expireAt,
        Integer needConfirm,
        @Size(max = 516) String linkUrl,
        @Size(max = 20) List<@Size(max = 20) String> channelIds,
        @Size(max = 68) String bizNo) {
}
