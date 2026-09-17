package fun.commons.notification4j.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * API-MSG-006 标记已读（ids ≤100 与 all 二选一）。
 * 显式 @JsonProperty：messageIds 经 snake_case 策略翻译在本工程 IT 中实测未绑定
 * （user_ids 同构却正常，差异未定位——评审跟踪），显式命名一劳永逸。
 */
public record PostReadRequest(
        @JsonProperty("message_ids") @Size(max = 100) List<String> messageIds,
        @JsonProperty("all") Boolean all) {
}
