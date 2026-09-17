package fun.commons.notification4j.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.Map;

/** API-PRK-001 签发注册码（max_uses/expire_hours/preset 预绑档；L1/L2/L3 信任分级为发码前运营决策） */
public record PostRegistrationKeysRequest(
        @Min(1) @Max(100) Integer maxUses,
        @Min(1) @Max(8760) Integer expireHours,
        Map<String, Object> preset) {
}
