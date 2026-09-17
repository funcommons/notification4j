package fun.commons.notification4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * API-MSG-001 发送定向消息（模板发送 V1.1；批量 >1000 V1.1 异步 Job）。
 * 校验对齐 DDL 列宽（评审第 3 步 P1：无 @Valid 时超参击穿列宽 → 500 而非 101xx）。
 */
public record PostMessagesRequest(
        @Size(max = 68) String bizNo,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_]{1,36}$") String typeCode,
        @Pattern(regexp = "NORMAL|IMPORTANT|URGENT") String level,
        // 注意：此 DTO 由 MSG-001(≤1000) 与 MSG-002 批量(≤100000) 共享——数量上限在各 service 校验
        // （评审第 10~16 步 P0：注解层 @Size(max=1000) 曾锁死批量契约）
        @NotEmpty List<@Size(min = 1, max = 68) String> userIds,
        @NotBlank @Size(max = 132) String title,
        @NotBlank @Size(max = 2000) String content,
        @Size(max = 500) @Pattern(regexp = "^https?://.*") String linkUrl) {
}
