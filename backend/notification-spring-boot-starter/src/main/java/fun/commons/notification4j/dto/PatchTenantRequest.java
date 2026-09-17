package fun.commons.notification4j.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/** API-PTE-002 配置修改（部分更新）：privileges{signature,delivery} / config{retentionDays,alert_user_ids,mail} / oem{theme,title,logo,hosts} */
@Data
public class PatchTenantRequest {
    @Size(max = 64, message = "name最长64字符")
    private String name;

    @Size(max = 512, message = "description最长512字符")
    private String description;

    private Map<String, Object> privileges;

    private Map<String, Object> config;

    private Map<String, Object> oem;
}
