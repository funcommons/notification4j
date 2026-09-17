package fun.commons.notification4j.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import fun.commons.notification4j.entity.NfyaChannel;
import fun.commons.notification4j.entity.NfyaDelivery;
import fun.commons.notification4j.entity.NfyaMessage;
import fun.commons.notification4j.entity.NfyaMessageRecipient;
import fun.commons.notification4j.entity.NfyaTenant;
import fun.commons.notification4j.mapper.NfyaChannelMapper;
import fun.commons.notification4j.mapper.NfyaDeliveryMapper;
import fun.commons.notification4j.mapper.NfyaMessageMapper;
import fun.commons.notification4j.mapper.NfyaMessageRecipientMapper;
import fun.commons.notification4j.mapper.NfyaTenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计概览（API-STAT-001 租户 + API-PST-001 平台，§5.9.2）。
 * 口径（评审 P2-3 统一）：
 * - today=自然日，时间基统一 created_at（含 today_delivered/today_deliveries——与成功/失败
 *   计数一致；不再按 sent_at，避免入队当日 vs 实发次日被拆到两天）；
 * - deliver_success_rate=SUCCESS/(SUCCESS+FAILED+DEAD)（今日），万分比定标（0~10000）；
 *   空数据日（当日无任何成败投递）语义与 read_rate_7d 对齐取 0——无样本不表示全成功；
 * - read_rate_7d=近 7 日投递消息的已读回执比例（万分比定标 0~10000，空数据取 0）；
 * - dead_count/dead_tenants=全时段 DEAD 口径（与 today 系列的时间窗差异：DEAD 为终态
 *   存量指标，需跨日追溯排查，故不设自然日窗口）；dead_tenants=DEAD 投递最多的租户 ≤20。
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    private final NfyaMessageMapper messageMapper;
    private final NfyaMessageRecipientMapper recipientMapper;
    private final NfyaDeliveryMapper deliveryMapper;
    private final NfyaChannelMapper channelMapper;
    private final NfyaTenantMapper tenantMapper;

    /** STAT-001 租户概览 */
    public Map<String, Object> tenantOverview(long tenantId) {
        OffsetDateTime dayStart = LocalDate.now().atStartOfDay().atOffset(OffsetDateTime.now().getOffset());
        Long todaySent = messageMapper.selectCount(new LambdaQueryWrapper<NfyaMessage>()
                .eq(NfyaMessage::getTenantId, tenantId)
                .ge(NfyaMessage::getCreatedAt, dayStart));
        Long ok = deliveryMapper.selectCount(new LambdaQueryWrapper<NfyaDelivery>()
                .eq(NfyaDelivery::getTenantId, tenantId)
                .eq(NfyaDelivery::getStatus, "SUCCESS")
                .ge(NfyaDelivery::getCreatedAt, dayStart));
        Long todayDelivered = ok; // 同口径（SUCCESS+自然日）复用一次查询结果（评审 P2：两条完全相同的 selectCount 合一）
        Long failed = deliveryMapper.selectCount(new LambdaQueryWrapper<NfyaDelivery>()
                .eq(NfyaDelivery::getTenantId, tenantId)
                .in(NfyaDelivery::getStatus, "FAILED", "DEAD")
                .ge(NfyaDelivery::getCreatedAt, dayStart));
        // 万分比定标（0~10000）；空数据日取 0 与 read_rate_7d 对齐（无样本 ≠ 全成功）
        long rate = (ok + failed) == 0 ? 0 : Math.round(ok * 10000.0 / (ok + failed));
        Long channelCount = channelMapper.selectCount(new LambdaQueryWrapper<NfyaChannel>()
                .eq(NfyaChannel::getTenantId, tenantId)
                .eq(NfyaChannel::getStatus, "ENABLED"));

        OffsetDateTime d7 = OffsetDateTime.now().minusDays(7);
        Long sent7d = recipientMapper.selectCount(new LambdaQueryWrapper<NfyaMessageRecipient>()
                .select(NfyaMessageRecipient::getId)
                .eq(NfyaMessageRecipient::getTenantId, tenantId)
                .ge(NfyaMessageRecipient::getCreatedAt, d7));
        Long read7d = recipientMapper.selectCount(new LambdaQueryWrapper<NfyaMessageRecipient>()
                .select(NfyaMessageRecipient::getId)
                .eq(NfyaMessageRecipient::getTenantId, tenantId)
                .eq(NfyaMessageRecipient::getReadStatus, "READ")
                .ge(NfyaMessageRecipient::getCreatedAt, d7));
        long readRate = sent7d == 0 ? 0 : Math.round(read7d * 10000.0 / sent7d);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("today_sent", todaySent);
        data.put("today_delivered", todayDelivered);
        data.put("deliver_success_rate", rate);
        data.put("read_rate_7d", readRate);
        data.put("channel_count", channelCount);
        return data;
    }

    /** PST-001 平台跨租户概览 */
    public Map<String, Object> platformOverview() {
        OffsetDateTime dayStart = LocalDate.now().atStartOfDay().atOffset(OffsetDateTime.now().getOffset());
        Long tenantCount = tenantMapper.selectCount(null);
        Long activeTenants = tenantMapper.selectCount(new LambdaQueryWrapper<NfyaTenant>()
                .eq(NfyaTenant::getStatus, "ACTIVE"));
        Long todayMessages = messageMapper.selectCount(new LambdaQueryWrapper<NfyaMessage>()
                .ge(NfyaMessage::getCreatedAt, dayStart));
        Long todayDeliveries = deliveryMapper.selectCount(new LambdaQueryWrapper<NfyaDelivery>()
                .eq(NfyaDelivery::getStatus, "SUCCESS")
                .ge(NfyaDelivery::getCreatedAt, dayStart));
        Long ok = deliveryMapper.selectCount(new LambdaQueryWrapper<NfyaDelivery>()
                .eq(NfyaDelivery::getStatus, "SUCCESS")
                .ge(NfyaDelivery::getCreatedAt, dayStart));
        Long failed = deliveryMapper.selectCount(new LambdaQueryWrapper<NfyaDelivery>()
                .in(NfyaDelivery::getStatus, "FAILED", "DEAD")
                .ge(NfyaDelivery::getCreatedAt, dayStart));
        // 万分比定标（0~10000）；空数据日取 0 与 read_rate_7d 对齐（无样本 ≠ 全成功）
        long rate = (ok + failed) == 0 ? 0 : Math.round(ok * 10000.0 / (ok + failed));
        Long deadCount = deliveryMapper.selectCount(new LambdaQueryWrapper<NfyaDelivery>()
                .eq(NfyaDelivery::getStatus, "DEAD"));

        // DEAD 最多的租户 top20（open_id 不出网给平台？平台域可见内部口径——输出 open_id）
        var deadTenants = deliveryMapper.selectMaps(new QueryWrapper<NfyaDelivery>()
                .select("tenant_id", "count(*) as cnt")
                .eq("status", "DEAD")
                .groupBy("tenant_id")
                .orderByDesc("cnt")
                .last("LIMIT 20"));
        List<Map<String, Object>> deadList = deadTenants.stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            Object tid = row.get("tenant_id");
            m.put("open_id", tid == null ? null : IdMaskHelper.toOpenId(tid));
            m.put("dead_count", row.get("cnt"));
            return m;
        }).toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tenant_count", tenantCount);
        data.put("active_tenants", activeTenants);
        data.put("today_messages", todayMessages);
        data.put("today_deliveries", todayDeliveries);
        data.put("deliver_success_rate", rate);
        data.put("dead_count", deadCount);
        data.put("dead_tenants", deadList);
        return data;
    }

    /** 平台域可见 open_id（内部 id 不出网口径沿用） */
    private static final class IdMaskHelper {
        private static String toOpenId(Object id) {
            try {
                return fun.commons.framework4j.id.util.IdObfuscator.toOpenId(Long.parseLong(String.valueOf(id)));
            } catch (Exception e) {
                return String.valueOf(id);
            }
        }
    }
}
