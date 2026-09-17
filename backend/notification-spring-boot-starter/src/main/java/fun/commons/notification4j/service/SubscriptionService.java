package fun.commons.notification4j.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.dto.PutSubscriptionsRequest;
import fun.commons.notification4j.entity.NfyaChannel;
import fun.commons.notification4j.entity.NfyaMessageType;
import fun.commons.notification4j.entity.NfyaSubscription;
import fun.commons.notification4j.mapper.NfyaChannelMapper;
import fun.commons.notification4j.mapper.NfyaSubscriptionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 订阅矩阵（API-SUB-001/002，§5.8）。
 * GET 三段：types（本租户启用类型）/ available_channels（ENABLED 渠道 + INAPP 哨兵）/ items（已配置，
 * 启用的 quiet_hours 原样回显——未启用行不返回该字段，缺省=未启用）。
 * PUT 全量替换：空 channel_ids 10101；channel_id 须属本人且 ENABLED 10400；
 * 强制集校验按「类型语义→实例」：mandatory 类型 default_channels 中每渠道类型，
 * 用户有该类型 ENABLED 实例则须保留 ≥1（关最后实例 10606），无实例豁免（INAPP 锁定兜底）；
 * quiet_hours（V1.2 第 20 步）可选免打扰时段：HH:mm 格式、start≠end、成对出现才算启用（违者 10100），
 * 缺省/{} = 未启用（全量替换语义下缺省即重置未启用）；延迟语义在 DeliveryPlanService 展开。
 * 渠道删除级联剔除失效 id（removeChannel，行保底回落 ["INAPP"]）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private static final String INAPP = "INAPP";

    /** HH:mm 24 小时制（00:00~23:59） */
    private static final java.util.regex.Pattern HH_MM = java.util.regex.Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    private final NfyaSubscriptionMapper subscriptionMapper;
    private final NfyaChannelMapper channelMapper;
    private final MessageTypeService messageTypeService;
    private final ObjectMapper objectMapper;

    /** API-SUB-001 我的订阅矩阵 */
    public Map<String, Object> get(long tenantId, String userid) {
        List<NfyaMessageType> types = messageTypeService.lambdaQuery()
                .eq(NfyaMessageType::getTenantId, tenantId)
                .eq(NfyaMessageType::getStatus, "ENABLED")
                .list();
        List<Map<String, Object>> typeItems = types.stream().map(t -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type_code", t.getTypeCode());
            item.put("name", t.getName());
            item.put("description", t.getDescription());
            item.put("mandatory", t.getMandatory());
            item.put("default_channels", parseArray(t.getDefaultChannels()));
            return item;
        }).toList();

        List<NfyaChannel> enabled = enabledChannels(tenantId, userid);
        List<Map<String, Object>> available = new ArrayList<>();
        available.add(Map.of("channel_id", INAPP));
        for (NfyaChannel ch : enabled) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("channel_id", String.valueOf(ch.getId()));
            item.put("channel_type", ch.getChannelType());
            item.put("name", ch.getName());
            available.add(item);
        }

        List<Map<String, Object>> items = rows(tenantId, userid).stream().map(r -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type_code", r.getTypeCode());
            item.put("channel_ids", parseArray(r.getChannelIds()));
            // 启用的免打扰时段原样回显；未启用（{}）不返回该字段（缺省=未启用，向后兼容旧客户端）
            Map<String, Object> quiet = parseObject(r.getQuietHours());
            if (!quiet.isEmpty()) {
                item.put("quiet_hours", quiet);
            }
            return item;
        }).toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("types", typeItems);
        data.put("available_channels", available);
        data.put("items", items);
        return data;
    }

    /** API-SUB-002 全量保存（PUT 幂等，last-write-wins；未提交的类型行删除） */
    @Transactional
    public Map<String, Object> save(long tenantId, String userid, PutSubscriptionsRequest req) {
        List<PutSubscriptionsRequest.Item> items = req.items() == null ? List.of() : req.items();
        List<NfyaChannel> enabled = enabledChannels(tenantId, userid);

        List<NormalizedRow> normalized = new ArrayList<>();
        Set<String> submittedTypes = new HashSet<>();
        Set<String> seenTypes = new HashSet<>();
        for (PutSubscriptionsRequest.Item item : items) {
            if (item.typeCode() == null || item.typeCode().isBlank()) {
                throw new ApiException(10101, "type_code 不能为空");
            }
            if (!seenTypes.add(item.typeCode())) {
                throw new ApiException(10100, "同一 type_code 重复提交: " + item.typeCode());
            }
            NfyaMessageType type = messageTypeService.requireEnabled(tenantId, item.typeCode());
            if (item.channelIds() == null || item.channelIds().isEmpty()) {
                throw new ApiException(10101, "channel_ids 至少 1 个");
            }
            Set<String> chosen = new java.util.LinkedHashSet<>();
            chosen.add(INAPP); // INAPP 哨兵锁定且恒在首位
            chosen.addAll(item.channelIds());
            for (String id : chosen) {
                if (!INAPP.equals(id)) {
                    ownedEnabledChannel(id, tenantId, userid); // 须属本人且 ENABLED → 10400
                }
            }
            enforceMandatory(type, chosen, enabled);
            String quietJson = normalizeQuietHours(item.quietHours());
            submittedTypes.add(item.typeCode());
            normalized.add(new NormalizedRow(item.typeCode(), toJson(new ArrayList<>(chosen)), quietJson));
        }

        // 全量替换：upsert 提交项 + 删除未提交项
        Map<String, NfyaSubscription> existing = new LinkedHashMap<>();
        for (NfyaSubscription r : rows(tenantId, userid)) {
            existing.put(r.getTypeCode(), r);
        }
        for (NormalizedRow n : normalized) {
            NfyaSubscription row = existing.get(n.typeCode());
            if (row == null) {
                row = new NfyaSubscription();
                row.setTenantId(tenantId);
                row.setUserid(userid);
                row.setTypeCode(n.typeCode());
                row.setChannelIds(n.channelIdsJson());
                row.setQuietHours(n.quietJson());
                row.setExt("{}");
                subscriptionMapper.insert(row);
            } else if (!n.channelIdsJson().equals(row.getChannelIds())
                    || !n.quietJson().equals(java.util.Objects.toString(row.getQuietHours(), "{}"))) {
                row.setChannelIds(n.channelIdsJson());
                row.setQuietHours(n.quietJson());
                subscriptionMapper.updateById(row);
            }
        }
        for (NfyaSubscription r : existing.values()) {
            if (!submittedTypes.contains(r.getTypeCode())) {
                subscriptionMapper.deleteById(r.getId());
            }
        }
        return Map.of("saved_count", normalized.size());
    }

    /**
     * 强制集校验（类型语义 → 实例）：mandatory=1 时 default_channels 中每个渠道类型，
     * 用户有该类型 ENABLED 实例则 chosen 须含 ≥1 个该类型实例；用户无实例 → 豁免（INAPP 兜底）。
     */
    private void enforceMandatory(NfyaMessageType type, Set<String> chosen, List<NfyaChannel> enabled) {
        if (type.getMandatory() == null || type.getMandatory() != 1) {
            return;
        }
        for (String ct : parseArray(type.getDefaultChannels())) {
            if (INAPP.equals(ct)) {
                continue; // INAPP 恒在
            }
            List<String> instancesOfType = enabled.stream()
                    .filter(c -> ct.equals(c.getChannelType()))
                    .map(c -> String.valueOf(c.getId()))
                    .toList();
            if (instancesOfType.isEmpty()) {
                continue; // 豁免：用户无该类型注册渠道
            }
            boolean kept = instancesOfType.stream().anyMatch(chosen::contains);
            if (!kept) {
                throw new ApiException(10606, "强制类型的指定渠道不可关闭");
            }
        }
    }

    /** 渠道删除级联：从本人订阅行剔除失效渠道 id（行保底回落 ["INAPP"]）；4a 删除接口调用 */
    @Transactional
    public void removeChannel(long tenantId, String userid, String channelId) {
        for (NfyaSubscription row : rows(tenantId, userid)) {
            List<String> ids = new ArrayList<>(parseArray(row.getChannelIds()));
            if (ids.remove(channelId)) {
                if (ids.isEmpty()) {
                    ids.add(INAPP);
                }
                row.setChannelIds(toJson(ids));
                subscriptionMapper.updateById(row);
            }
        }
    }

    private NfyaChannel ownedEnabledChannel(String channelId, long tenantId, String userid) {
        long id;
        try {
            id = Long.parseLong(channelId);
        } catch (NumberFormatException e) {
            throw new ApiException(10400, "渠道不存在或不可用");
        }
        NfyaChannel ch = channelMapper.selectById(id);
        if (ch == null || ch.getTenantId() != tenantId || !userid.equals(ch.getUserid())
                || !"USER".equals(ch.getScope()) || !"ENABLED".equals(ch.getStatus())) {
            throw new ApiException(10400, "渠道不存在或不可用");
        }
        return ch;
    }

    private List<NfyaChannel> enabledChannels(long tenantId, String userid) {
        return channelMapper.selectList(new LambdaQueryWrapper<NfyaChannel>()
                .eq(NfyaChannel::getTenantId, tenantId)
                .eq(NfyaChannel::getUserid, userid)
                .eq(NfyaChannel::getScope, "USER")
                .eq(NfyaChannel::getStatus, "ENABLED"));
    }

    private List<NfyaSubscription> rows(long tenantId, String userid) {
        return subscriptionMapper.selectList(new LambdaQueryWrapper<NfyaSubscription>()
                .eq(NfyaSubscription::getTenantId, tenantId)
                .eq(NfyaSubscription::getUserid, userid));
    }

    private List<String> parseArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            // 强制集校验 fail-open（空列表豁免）可观测化：损坏 jsonb 至少告警（评审第 4 步 P2）
            log.warn("[Subscription] channel_ids/default_channels jsonb 解析失败: {}", json, e);
            return List.of();
        }
    }

    private String toJson(List<String> ids) {
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            throw new IllegalStateException("channel_ids 序列化失败", e);
        }
    }

    /**
     * 免打扰时段归一化（V1.2 第 20 步）：null 或 start/end 均缺省 → "{}"（未启用）；
     * 只给其一 → 10100（成对出现才算启用）；格式非 HH:mm → 10100；start=end → 10100（空窗无意义）。
     * 启用 → {"start":"HH:mm","end":"HH:mm"}（跨午夜允许，判定口径在 DeliveryPlanService）。
     */
    private String normalizeQuietHours(PutSubscriptionsRequest.QuietHours qh) {
        if (qh == null) {
            return "{}";
        }
        boolean hasStart = qh.start() != null && !qh.start().isBlank();
        boolean hasEnd = qh.end() != null && !qh.end().isBlank();
        if (!hasStart && !hasEnd) {
            return "{}";
        }
        if (!hasStart || !hasEnd) {
            throw new ApiException(10100, "免打扰 start 与 end 必须成对提供");
        }
        String start = qh.start().trim();
        String end = qh.end().trim();
        if (!HH_MM.matcher(start).matches() || !HH_MM.matcher(end).matches()) {
            throw new ApiException(10100, "免打扰时间格式须为 HH:mm");
        }
        if (start.equals(end)) {
            throw new ApiException(10100, "免打扰开始与结束时间不能相同");
        }
        try {
            Map<String, String> ordered = new LinkedHashMap<>();
            ordered.put("start", start);
            ordered.put("end", end);
            return objectMapper.writeValueAsString(ordered);
        } catch (Exception e) {
            throw new IllegalStateException("quiet_hours 序列化失败", e);
        }
    }

    /** quiet_hours 回显解析：脏数据 fail-open 回 {}（与 parseArray 同口径，至少告警） */
    private Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            log.warn("[Subscription] quiet_hours jsonb 解析失败: {}", json, e);
            return Map.of();
        }
    }

    /** PUT 归一化行：typeCode + channel_ids json + quiet_hours json */
    private record NormalizedRow(String typeCode, String channelIdsJson, String quietJson) {
    }
}
