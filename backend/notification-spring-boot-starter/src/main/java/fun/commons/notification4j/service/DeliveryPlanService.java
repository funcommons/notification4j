package fun.commons.notification4j.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.notification4j.entity.NfyaAnnouncement;
import fun.commons.notification4j.entity.NfyaChannel;
import fun.commons.notification4j.entity.NfyaDelivery;
import fun.commons.notification4j.entity.NfyaMessageType;
import fun.commons.notification4j.entity.NfyaSubscription;
import fun.commons.notification4j.mapper.NfyaChannelMapper;
import fun.commons.notification4j.mapper.NfyaDeliveryMapper;
import fun.commons.notification4j.mapper.NfyaMessageTypeMapper;
import fun.commons.notification4j.mapper.NfyaSubscriptionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 投递计划（第 6a 步）：send/publish 时展开订阅矩阵 → nfya_delivery PENDING 行（引擎执行在 6b）。
 * 展开规则（PRD F-MSG-001/§5.8）：
 * - 无订阅行：类型 default_channels「类型语义 → 用户该类型全部 ENABLED 实例」；
 * - 有订阅行：channel_ids 实例（计划时即过滤非 ENABLED/已删）；
 * - URGENT 等级：全部 ENABLED 注册渠道（无视矩阵勾选）；
 * - INAPP 哨兵不产 delivery 行（站内信已同步落库）；
 * - 免打扰时段 quiet_hours（V1.2 第 20 步，Courier 口径=**推迟发送非丢弃**）：展开订阅矩阵时当前时刻
 *   处于该订阅静默窗 → 生成的外发投递 next_retry_at=窗结束时刻（引擎按 next_retry_at<=now 扫描，
 *   窗结束自动发出，本类与 DeliveryEngine 均不丢弃/改写投递）。
 *   天然豁免：URGENT 走全量渠道路径不经订阅矩阵（完全忽略免打扰）；INAPP 站内信落库即达不走投递。
 * 目标快照脱敏落库（DBD：target 脱敏后再落快照）；uk_nfya_delivery_source 幂等（重复计划静默跳过）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryPlanService {

    private static final String ANNOUNCEMENT_TYPE = "ANNOUNCEMENT";

    private final NfyaChannelMapper channelMapper;
    private final NfyaSubscriptionMapper subscriptionMapper;
    private final NfyaMessageTypeMapper messageTypeMapper;
    private final NfyaDeliveryMapper deliveryMapper;
    private final ObjectMapper objectMapper;

    /** 定向消息展开（send 事务内调用；返回计划行数） */
    @Transactional
    public int planForMessage(long tenantId, Long messageId, String typeCode, String level,
                              String title, List<String> userIds) {
        NfyaMessageType type = messageTypeMapper.selectOne(new LambdaQueryWrapper<NfyaMessageType>()
                .eq(NfyaMessageType::getTenantId, tenantId)
                .eq(NfyaMessageType::getTypeCode, typeCode)
                .last("LIMIT 1"));
        boolean urgent = "URGENT".equals(level);
        int planned = 0;
        for (String userid : userIds) {
            UserTargets t = resolveTargets(tenantId, userid, type, urgent);
            planned += insertRows(tenantId, "MESSAGE", messageId, userid, title, t.targets(), t.quietEnd());
        }
        return planned;
    }

    /** 公告发布展开：勾选公共渠道（userid=''）+ 订阅 ANNOUNCEMENT 类型用户的站外实例渠道 */
    @Transactional
    public int planForAnnouncement(NfyaAnnouncement a) {
        int planned = 0;
        // 1) 公共渠道：channel_ids 引用且属本租户 scope=TENANT ENABLED
        List<NfyaChannel> publicChannels = new ArrayList<>();
        for (String id : parseArray(a.getChannelIds())) {
            try {
                NfyaChannel ch = channelMapper.selectById(Long.valueOf(id));
                if (ch != null && ch.getTenantId() != null && ch.getTenantId().equals(a.getTenantId())
                        && "TENANT".equals(ch.getScope()) && "ENABLED".equals(ch.getStatus())) {
                    publicChannels.add(ch);
                }
            } catch (NumberFormatException ignored) {
                // 非数字 id 跳过（历史脏数据防御）
            }
        }
        planned += insertRows(a.getTenantId(), "ANNOUNCEMENT", a.getId(), "", a.getTitle(), publicChannels, null);

        // 2) 订阅 ANNOUNCEMENT 的用户：订阅行实例渠道（ENABLED 过滤；quiet_hours 命中静默窗则推迟）
        List<NfyaSubscription> subs = subscriptionMapper.selectList(new LambdaQueryWrapper<NfyaSubscription>()
                .eq(NfyaSubscription::getTenantId, a.getTenantId())
                .eq(NfyaSubscription::getTypeCode, ANNOUNCEMENT_TYPE));
        for (NfyaSubscription s : subs) {
            List<NfyaChannel> enabled = enabledChannels(a.getTenantId(), s.getUserid());
            Set<String> chosen = new HashSet<>(parseArray(s.getChannelIds()));
            List<NfyaChannel> targets = enabled.stream()
                    .filter(c -> chosen.contains(String.valueOf(c.getId())))
                    .toList();
            planned += insertRows(a.getTenantId(), "ANNOUNCEMENT", a.getId(), s.getUserid(), a.getTitle(),
                    targets, quietWindowEnd(s.getQuietHours(), OffsetDateTime.now()));
        }
        return planned;
    }

    /** 单用户展开结果：targets 目标渠道；quietEnd 静默窗命中时的窗结束时刻（null=即时可扫） */
    private record UserTargets(List<NfyaChannel> targets, OffsetDateTime quietEnd) {
    }

    /** 单用户目标渠道解析：URGENT 全量（免打扰豁免）/ 订阅行实例（含 quiet_hours 判定）/ 默认类型语义 */
    private UserTargets resolveTargets(long tenantId, String userid, NfyaMessageType type, boolean urgent) {
        List<NfyaChannel> enabled = enabledChannels(tenantId, userid);
        if (urgent) {
            return new UserTargets(enabled, null); // 全部 ENABLED 注册渠道；不经订阅矩阵 → 免打扰天然豁免
        }
        if (type == null) {
            return new UserTargets(List.of(), null);
        }
        NfyaSubscription row = subscriptionMapper.selectOne(new LambdaQueryWrapper<NfyaSubscription>()
                .eq(NfyaSubscription::getTenantId, tenantId)
                .eq(NfyaSubscription::getUserid, userid)
                .eq(NfyaSubscription::getTypeCode, type.getTypeCode())
                .last("LIMIT 1"));
        if (row != null) {
            Set<String> chosen = new HashSet<>(parseArray(row.getChannelIds()));
            return new UserTargets(
                    enabled.stream().filter(c -> chosen.contains(String.valueOf(c.getId()))).toList(),
                    quietWindowEnd(row.getQuietHours(), OffsetDateTime.now()));
        }
        // 无订阅行：default_channels 类型语义 → 该类型全部 ENABLED 实例
        Set<String> defaultTypes = new HashSet<>(parseArray(type.getDefaultChannels()));
        return new UserTargets(
                enabled.stream().filter(c -> defaultTypes.contains(c.getChannelType())).toList(), null);
    }

    private List<NfyaChannel> enabledChannels(long tenantId, String userid) {
        return channelMapper.selectList(new LambdaQueryWrapper<NfyaChannel>()
                .eq(NfyaChannel::getTenantId, tenantId)
                .eq(NfyaChannel::getUserid, userid)
                .eq(NfyaChannel::getScope, "USER")
                .eq(NfyaChannel::getStatus, "ENABLED"));
    }

    private int insertRows(long tenantId, String sourceType, Long sourceId, String userid,
                           String title, List<NfyaChannel> targets, OffsetDateTime quietEnd) {
        // 免打扰命中 → next_retry_at=窗结束时刻（推迟发送非丢弃）；否则维持现状即时可扫
        OffsetDateTime nextRetry = quietEnd == null ? OffsetDateTime.now() : quietEnd;
        int inserted = 0;
        for (NfyaChannel ch : targets) {
            NfyaDelivery d = new NfyaDelivery();
            d.setTenantId(tenantId);
            d.setSourceType(sourceType);
            d.setSourceId(sourceId);
            d.setUserid(userid);
            d.setChannelId(ch.getId());
            d.setChannelType(ch.getChannelType());
            // 快照脱敏落库（DBD 表 10 敏感列约定）
            d.setTarget(ChannelService.mask(ch));
            d.setTitle(title == null ? "" : title);
            d.setStatus("PENDING");
            d.setRetryCount(0);
            d.setNextRetryAt(nextRetry);
            d.setErrorMessage("");
            d.setTraceId("");
            d.setExt("{}");
            try {
                deliveryMapper.insert(d);
                inserted++;
            } catch (DuplicateKeyException e) {
                // uk_nfya_delivery_source 幂等：重放/重复计划静默跳过
            }
        }
        return inserted;
    }

    /**
     * 免打扰静默窗判定（V1.2 第 20 步）：订阅 quiet_hours={"start":"HH:mm","end":"HH:mm"}，
     * '{}'/缺省/脏数据 fail-open 返回 null（即时可扫，与 channel_ids 解析同口径）。
     * 判定用系统默认时区；跨午夜窗（如 22:00→08:00）命中=t≥start 或 t<end；同日窗=t∈[start,end)。
     * 命中返回窗结束时刻：窗前段（如 01:00 处于 22:00→08:00 前段）=当日 end；窗后段（如 23:00）=次日 end。
     * start=end（保存侧已禁，脏数据防御）视为空窗不静默。
     */
    private OffsetDateTime quietWindowEnd(String quietHours, OffsetDateTime now) {
        if (quietHours == null || quietHours.isBlank() || "{}".equals(quietHours.trim())) {
            return null;
        }
        Map<String, Object> m;
        try {
            m = objectMapper.readValue(quietHours, objectMapper.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            log.warn("[Delivery] quiet_hours jsonb 解析失败(fail-open 即时投递): {}", quietHours);
            return null;
        }
        if (!(m.get("start") instanceof String start) || !(m.get("end") instanceof String end)
                || start.isBlank() || end.isBlank()) {
            return null;
        }
        try {
            java.time.LocalTime startTime = java.time.LocalTime.parse(start.trim());
            java.time.LocalTime endTime = java.time.LocalTime.parse(end.trim());
            if (startTime.equals(endTime)) {
                return null; // 空窗防御
            }
            java.time.ZonedDateTime nowZ = now.atZoneSameInstant(java.time.ZoneId.systemDefault());
            java.time.LocalTime cur = nowZ.toLocalTime();
            boolean crossMidnight = startTime.isAfter(endTime);
            boolean inWindow = crossMidnight
                    ? (!cur.isBefore(startTime) || cur.isBefore(endTime))   // t≥start 或 t<end
                    : (!cur.isBefore(startTime) && cur.isBefore(endTime));  // t∈[start,end)
            if (!inWindow) {
                return null;
            }
            java.time.ZonedDateTime endAt = nowZ.with(java.time.LocalTime.of(endTime.getHour(), endTime.getMinute()));
            if (!endAt.isAfter(nowZ)) {
                endAt = endAt.plusDays(1); // 窗后段：窗结束在次日（如 23:00 处于 22:00→08:00）
            }
            return endAt.toOffsetDateTime();
        } catch (Exception e) {
            log.warn("[Delivery] quiet_hours 时刻解析失败(fail-open 即时投递): {}", quietHours);
            return null;
        }
    }

    private List<String> parseArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            log.warn("[Delivery] channel_ids jsonb 解析失败: {}", json);
            return List.of();
        }
    }
}
