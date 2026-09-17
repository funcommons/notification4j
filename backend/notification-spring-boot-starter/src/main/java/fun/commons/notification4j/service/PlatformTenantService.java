package fun.commons.notification4j.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.id.util.IdObfuscator;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.dto.PatchTenantRequest;
import fun.commons.notification4j.entity.NfyaTenant;
import fun.commons.notification4j.mapper.NfyaDeliveryMapper;
import fun.commons.notification4j.mapper.NfyaTenantMapper;
import fun.commons.framework4j.tenant.auth.TenantSessionRevoker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 租户生命周期（API-PTE-001~004，§4.4/§5.9.2；平台域）。
 * 创建 → {open_id, tenant_secret}（明文仅此一次；内部雪花 id 不出网）；email 唯一 10401。
 * reset-secret：新明文一次，旧密钥进 prev（SEC-001 同列同口径，第 11 步加密教训沿用：
 * 密文列写走实体 typeHandler；privileges/config/oem Map+jsonb 用 setSql ::jsonb 参数化写）。
 * 状态机：ACTIVE⇄SUSPENDED→CLOSED（终态 10402）；SUSPEND 后 TenantAuthEndpoint 401 同码防探测。
 */
@Slf4j
@Service
public class PlatformTenantService {

    private static final Set<String> LEGAL_ACTIONS = Set.of("SUSPEND", "RESUME", "CLOSE");

    private final NfyaTenantMapper tenantMapper;
    private final NfyaDeliveryMapper deliveryMapper;
    private final ObjectMapper objectMapper;
    /** 可选：无 Redis 上下文为 null（撤销失败留痕不阻断，§5.9.2 PTE-003） */
    private final ObjectProvider<TenantSessionRevoker> sessionRevoker;

    public PlatformTenantService(NfyaTenantMapper tenantMapper, NfyaDeliveryMapper deliveryMapper,
                                 ObjectMapper objectMapper, ObjectProvider<TenantSessionRevoker> sessionRevoker) {
        this.tenantMapper = tenantMapper;
        this.deliveryMapper = deliveryMapper;
        this.objectMapper = objectMapper;
        this.sessionRevoker = sessionRevoker;
    }

    /** 撤销全部存量会话（评审第 10~16 步 P0：auto-renew 放大下不撤销=状态变更形同虚设；失败留痕不阻断） */
    private void revokeSessions(long tenantId) {
        TenantSessionRevoker revoker = sessionRevoker.getIfAvailable();
        if (revoker != null) {
            try {
                revoker.revoke(tenantId);
                log.info("[PTE] 已撤销租户 {} 全部存量会话", tenantId);
            } catch (Exception e) {
                log.warn("[PTE] 租户 {} 会话撤销失败(不阻断): {}", tenantId, e.getMessage());
            }
        }
    }

    /** PTE-001 创建 */
    @Transactional
    public Map<String, Object> create(fun.commons.notification4j.dto.PostTenantsRequest req) {
        String secret = newSecret();
        NfyaTenant t = new NfyaTenant();
        t.setName(req.getName());
        t.setDescription(req.getDescription() == null ? "" : req.getDescription());
        t.setEmail(req.getEmail() == null ? "" : req.getEmail());
        t.setStatus("ACTIVE");
        t.setTenantSecret(secret);
        // privileges/config/oem 走 insert 后的参数化 jsonb 更新（Map+jsonb 不能经实体 insert，第 11 步口径）
        try {
            tenantMapper.insert(t);
        } catch (DuplicateKeyException e) {
            throw new ApiException(10401, "邮箱已被使用");
        }
        if (req.getPrivileges() != null) {
            patchJsonColumn(t.getId(), "privileges", req.getPrivileges());
        }
        if (req.getConfig() != null) {
            patchJsonColumn(t.getId(), "config", req.getConfig());
        }
        if (req.getOem() != null) {
            patchJsonColumn(t.getId(), "oem", req.getOem());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("open_id", IdObfuscator.toOpenId(t.getId()));
        data.put("tenant_secret", secret);
        return data;
    }

    /** PTE-001 列表 */
    public Map<String, Object> list() {
        List<NfyaTenant> rows = tenantMapper.selectList(new LambdaQueryWrapper<NfyaTenant>()
                .orderByDesc(NfyaTenant::getCreatedAt));
        List<Map<String, Object>> items = rows.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("open_id", IdObfuscator.toOpenId(t.getId()));
            m.put("name", t.getName());
            m.put("email", maskEmail(t.getEmail()));
            m.put("status", t.getStatus());
            m.put("created_at", t.getCreatedAt() == null ? null : t.getCreatedAt().toInstant().toEpochMilli());
            return m;
        }).toList();
        return Map.of("list", items);
    }

    /** PTE-002 详情 */
    public Map<String, Object> detail(String openId) {
        NfyaTenant t = requireTenant(openId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("open_id", openId);
        m.put("name", t.getName());
        m.put("description", t.getDescription());
        m.put("email", maskEmail(t.getEmail()));
        m.put("status", t.getStatus());
        m.put("privileges", parseObj(jsonOf(t.getId(), "privileges")));
        m.put("config", parseObj(jsonOf(t.getId(), "config")));
        m.put("oem", parseObj(jsonOf(t.getId(), "oem")));
        m.put("created_at", t.getCreatedAt() == null ? null : t.getCreatedAt().toInstant().toEpochMilli());
        return m;
    }

    /** PTE-002 配置修改（privileges/config/oem 参数化 jsonb 写；标量字段有值才发标量 update——防空 SET） */
    @Transactional
    public Map<String, Object> patch(String openId, PatchTenantRequest req) {
        long tenantId = requireTenantId(openId);
        if (req.getName() != null || req.getDescription() != null) {
            LambdaUpdateWrapper<NfyaTenant> uw = new LambdaUpdateWrapper<NfyaTenant>()
                    .eq(NfyaTenant::getId, tenantId);
            if (req.getName() != null) uw.set(NfyaTenant::getName, req.getName());
            if (req.getDescription() != null) uw.set(NfyaTenant::getDescription, req.getDescription());
            tenantMapper.update(null, uw);
        }
        if (req.getPrivileges() != null) patchJsonColumn(tenantId, "privileges", req.getPrivileges());
        if (req.getConfig() != null) patchJsonColumn(tenantId, "config", req.getConfig());
        if (req.getOem() != null) patchJsonColumn(tenantId, "oem", req.getOem());
        return Map.of("open_id", openId);
    }

    /** PTE-003 重置密钥：新明文一次；旧密钥进 prev（SEC-001 同口径） */
    @Transactional
    public Map<String, Object> resetSecret(String openId) {
        NfyaTenant t = requireTenant(openId);
        String old = t.getTenantSecret();
        String fresh = newSecret();
        t.setPrivileges(null);
        t.setConfig(null);
        t.setOem(null);
        t.setExt(null);
        if (old != null && !old.isBlank()) {
            t.setTenantSecretPrev(old);
            t.setTenantSecretPrevAt(OffsetDateTime.now());
        }
        t.setTenantSecret(fresh);
        tenantMapper.updateById(t);
        return Map.of("open_id", openId, "tenant_secret", fresh);
    }

    /** PTE-004 状态机：ACTIVE⇄SUSPENDED→CLOSED（终态） */
    @Transactional
    public Map<String, Object> changeStatus(String openId, String action) {
        if (action == null || !LEGAL_ACTIONS.contains(action)) {
            throw new ApiException(10100, "action 仅允许 SUSPEND/RESUME/CLOSE");
        }
        long tenantId = requireTenantId(openId);
        NfyaTenant t = requireTenant(openId);
        String cur = t.getStatus();
        String next;
        switch (action) {
            case "SUSPEND" -> {
                if (!"ACTIVE".equals(cur)) throw new ApiException(10402, "状态机非法迁移");
                next = "SUSPENDED";
            }
            case "RESUME" -> {
                if (!"SUSPENDED".equals(cur)) throw new ApiException(10402, "状态机非法迁移");
                next = "ACTIVE";
            }
            default -> { // CLOSE：契约前置「无未投递任务」（冷静期登记 V1.1）
                long pending = deliveryMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<fun.commons.notification4j.entity.NfyaDelivery>()
                        .eq(fun.commons.notification4j.entity.NfyaDelivery::getTenantId, tenantId)
                        .in(fun.commons.notification4j.entity.NfyaDelivery::getStatus, "PENDING", "SENDING"));
                if (pending > 0) {
                    throw new ApiException(10402, "存在未完成投递任务 " + pending + " 条，暂不可注销");
                }
                if ("CLOSED".equals(cur)) throw new ApiException(10402, "状态机非法迁移");
                next = "CLOSED";
            }
        }
        // 精准 set status：全实体回写会在并发下覆盖密钥列（评审第 10~16 步 P1）
        tenantMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<NfyaTenant>()
                .eq(NfyaTenant::getId, tenantId)
                .set(NfyaTenant::getStatus, next));
        if ("SUSPEND".equals(action) || "CLOSE".equals(action)) {
            revokeSessions(tenantId); // SUSPEND/CLOSE 撤销存量会话
        }
        return Map.of("open_id", openId, "status", next);
    }

    /** Map+jsonb 参数化写（::jsonb cast 绕开 varchar↔jsonb，第 11 步口径） */
    private void patchJsonColumn(long tenantId, String column, Map<String, Object> value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            tenantMapper.update(null, new UpdateWrapper<NfyaTenant>()
                    .eq("id", tenantId)
                    .setSql(column + " = {0}::jsonb", json));
        } catch (Exception e) {
            throw new IllegalStateException(column + " 序列化失败", e);
        }
    }

    /** 读 jsonb 列为文本（::text），解析交给 parseObj——避开实体 Map 字段无 autoResultMap 的读取盲区 */
    private String jsonOf(long tenantId, String column) {
        var rows = tenantMapper.selectMaps(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<NfyaTenant>()
                .select(column + "::text as json_val")
                .eq("id", tenantId));
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        Object v = rows.get(0).get("json_val");
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseObj(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private NfyaTenant requireTenant(String openId) {
        return tenantMapper.selectById(requireTenantId(openId));
    }

    private long requireTenantId(String openId) {
        try {
            long id = IdObfuscator.fromOpenId(openId);
            NfyaTenant t = tenantMapper.selectById(id);
            if (t == null) {
                throw new ApiException(10400, "租户不存在");
            }
            return id;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(10400, "租户不存在");
        }
    }

    private String newSecret() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String prefix = local.substring(0, Math.min(1, local.length()));
        String suffix = local.length() > 1 ? local.substring(local.length() - 1) : "";
        return prefix + "***" + suffix + email.substring(at);
    }
}
