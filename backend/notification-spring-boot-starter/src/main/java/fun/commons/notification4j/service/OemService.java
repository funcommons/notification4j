package fun.commons.notification4j.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.notification4j.entity.NfyaTenant;
import fun.commons.notification4j.mapper.NfyaTenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 租户 OEM 配置读面（API-OEM-001，V1.3；GitHub issue #1 补实现）。
 * <p>
 * 下发本租户 {@code oem.hosts} —— 嵌入消息中心 postMessage origin 白名单的
 * 运行时面（与前端构建时 {@code VITE_NFY_PARENT_ORIGINS} 取并集生效）。
 * 此前 oem 仅有平台面写入与存储，无任何消费方，白名单实际只有构建时半边。
 * <p>
 * 读通道沿用 PlatformTenantService 口径：{@code oem::text} 文本读后解析，
 * 避开实体 Map 字段无 autoResultMap 的读取盲区；oem 缺省/损坏/hosts 非数组
 * 一律按空白名单返回（不阻断握手，fail-closed）。
 */
@Service
@RequiredArgsConstructor
public class OemService {

    private final NfyaTenantMapper tenantMapper;
    private final ObjectMapper objectMapper;

    /** API-OEM-001 本租户 oem.hosts（T 鉴权无 U；嵌入面握手前核验用） */
    public Map<String, Object> hosts(long tenantId) {
        return Map.of("hosts", hostList(tenantId));
    }

    /** oem.hosts 提取：非字符串/空白项过滤；任何异常 → 空列表（fail-closed） */
    public List<String> hostList(long tenantId) {
        String json = jsonOf(tenantId, "oem");
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Object hosts = objectMapper.readValue(json, Map.class).get("hosts");
            if (!(hosts instanceof List<?> list)) {
                return List.of();
            }
            return list.stream().filter(h -> h != null && !String.valueOf(h).isBlank())
                    .map(String::valueOf).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 读 jsonb 列为文本（::text）——与 PlatformTenantService.jsonOf 同口径 */
    private String jsonOf(long tenantId, String column) {
        var rows = tenantMapper.selectMaps(new QueryWrapper<NfyaTenant>()
                .select(column + "::text as json_val")
                .eq("id", tenantId));
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        Object v = rows.get(0).get("json_val");
        return v == null ? null : String.valueOf(v);
    }
}
