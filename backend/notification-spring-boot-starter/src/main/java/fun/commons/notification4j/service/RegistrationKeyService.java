package fun.commons.notification4j.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.id.util.IdObfuscator;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.entity.NfyaTenant;
import fun.commons.notification4j.entity.NfypRegistrationKey;
import fun.commons.notification4j.mapper.NfypRegistrationKeyMapper;
import fun.commons.notification4j.mapper.NfyaTenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 注册码闭环（API-PRK-001 平台签发 + API-OPEN-001 开放域自助注册，§4.1/§4.4/§5.9.2）。
 * 签发：随机码落库 ACTIVE + preset 预绑档（一次性显示）；列表 code 脱敏。
 * 注册：DB 单语句原子扣减（used_count<max_uses AND ACTIVE AND 未过期）→ 10608 同码防探测；
 * 成功 → 创建租户（明文密钥一次，PlatformTenantService 同构）+ 回填 consumed_tenant_id + preset 预绑。
 * Redis 原子扣减为高并发优化项（技术方案 §5.9.2），V1.0 低频写场景以 DB CAS 兜底。
 */
@Service
@RequiredArgsConstructor
public class RegistrationKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final NfypRegistrationKeyMapper keyMapper;
    private final NfyaTenantMapper tenantMapper;
    private final ObjectMapper objectMapper;

    /** PRK-001 签发（平台域） */
    @Transactional
    public Map<String, Object> issue(Long maxUses, Long expireHours, Map<String, Object> preset, String issueBy) {
        int uses = maxUses == null ? 1 : Math.max(1, maxUses.intValue());
        int hours = expireHours == null ? 24 : Math.max(1, expireHours.intValue());
        String code = randomCode();
        NfypRegistrationKey key = new NfypRegistrationKey();
        key.setCode(code);
        key.setMaxUses(uses);
        key.setUsedCount(0);
        key.setPreset(toJson(preset == null ? Map.of() : preset));
        key.setStatus("ACTIVE");
        key.setExpireAt(OffsetDateTime.now().plusHours(hours));
        key.setConsumedTenantId(0L);
        key.setIssueBy(issueBy == null ? "" : issueBy);
        key.setExt("{}");
        keyMapper.insert(key);
        return Map.of("registration_key", code, "max_uses", uses, "expire_hours", hours);
    }

    /** PRK-001 列表（code 脱敏） */
    public Map<String, Object> list() {
        List<NfypRegistrationKey> rows = keyMapper.selectList(new LambdaQueryWrapper<NfypRegistrationKey>()
                .orderByDesc(NfypRegistrationKey::getCreatedAt));
        List<Map<String, Object>> items = rows.stream().map(k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code_masked", maskCode(k.getCode()));
            m.put("max_uses", k.getMaxUses());
            m.put("used_count", k.getUsedCount());
            m.put("status", k.getStatus());
            m.put("expire_at", k.getExpireAt() == null ? null : k.getExpireAt().toInstant().toEpochMilli());
            // 未消费（0）输出 null，避免貌似 open_id 的误导值（评审 P2）
            m.put("consumed_open_id", k.getConsumedTenantId() == null || k.getConsumedTenantId() == 0L
                    ? null : IdObfuscator.toOpenId(k.getConsumedTenantId()));
            return m;
        }).toList();
        return Map.of("list", items);
    }

    /** OPEN-001 注册（开放域无鉴权）：原子扣减 → 建租户 → 明文一次；失败 10608 同码防探测 */
    @Transactional
    public Map<String, Object> register(String code, String name) {
        if (code == null || code.isBlank()) {
            throw new ApiException(10608, "注册码无效或已失效");
        }
        NfypRegistrationKey key = keyMapper.selectOne(new LambdaQueryWrapper<NfypRegistrationKey>()
                .eq(NfypRegistrationKey::getCode, code)
                .last("LIMIT 1"));
        if (key == null) {
            throw new ApiException(10608, "注册码无效或已失效");
        }
        // 先建租户（拿雪花 id），再原子扣减并回填；扣减失败抛 10608 → 事务回滚建租户。
        // email 必须为 null：TCK 唯一索引谓词是 email IS NOT NULL，空串''会计入索引导致第二个自助租户撞键
        NfyaTenant t = new NfyaTenant();
        t.setName(name == null || name.isBlank() ? "注册码租户" : name);
        t.setDescription("");
        t.setEmail(null);
        t.setStatus("ACTIVE");
        t.setTenantSecret(newSecret());
        tenantMapper.insert(t);
        int affected = keyMapper.consume(code, t.getId());
        if (affected == 0) {
            throw new ApiException(10608, "注册码无效或已失效");
        }
        applyPreset(t.getId(), key.getPreset());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("open_id", IdObfuscator.toOpenId(t.getId()));
        data.put("tenant_secret", t.getTenantSecret());
        return data;
    }

    /** preset 预绑（privileges/config/oem）到新租户 */
    private void applyPreset(long tenantId, String presetJson) {
        if (presetJson == null || presetJson.isBlank()) {
            return;
        }
        try {
            Map<String, Object> preset = objectMapper.readValue(presetJson, Map.class);
            for (String column : List.of("privileges", "config", "oem")) {
                Object v = preset.get(column);
                if (v != null) {
                    String json = objectMapper.writeValueAsString(v);
                    tenantMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<NfyaTenant>()
                            .eq("id", tenantId)
                            .setSql(column + " = {0}::jsonb", json));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("preset 应用失败", e);
        }
    }

    private String newSecret() {
        return randomHex(24) + randomHex(24);
    }

    private String randomCode() {
        return "nfy-" + randomHex(20);
    }

    private String randomHex(int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append("0123456789abcdef".charAt(RANDOM.nextInt(16)));
        }
        return sb.toString();
    }

    static String maskCode(String code) {
        if (code == null || code.length() <= 8) {
            return "****";
        }
        return code.substring(0, 8) + "****";
    }

    private String toJson(Map<String, Object> v) {
        try {
            return objectMapper.writeValueAsString(v);
        } catch (Exception e) {
            return "{}";
        }
    }
}
