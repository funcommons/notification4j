package fun.commons.notification4j.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/** API-PTE-001 创建租户（§5.9.2）：→ {open_id, tenant_secret}（明文仅此一次） */
@Data
public class PostTenantsRequest {
    @NotBlank(message = "name不能为空")
    @Size(max = 64, message = "name最长64字符")
    private String name;

    @NotBlank(message = "email不能为空")
    @Email(message = "email格式非法")
    @Size(max = 259, message = "email最长259字符")
    private String email;

    @Size(max = 512, message = "description最长512字符")
    private String description;

    /** 三组安全/参数/白标配置（结构契约见 §5.9.2 PTE-002） */
    private Map<String, Object> privileges;

    private Map<String, Object> config;

    private Map<String, Object> oem;
}
