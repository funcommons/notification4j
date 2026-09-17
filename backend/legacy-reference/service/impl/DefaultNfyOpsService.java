package fun.commons.notification4j.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import fun.commons.notification4j.dto.*;
import fun.commons.notification4j.entity.*;
import fun.commons.notification4j.mapper.*;
import fun.commons.notification4j.service.NfyOpsService;
import fun.commons.framework4j.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DefaultNfyOpsService implements NfyOpsService {

    private final NfyaSubscribeMapper subscribeMapper;
    private final NfyaSubscribeItemMapper subscribeItemMapper;
    private final NfyaNfySetMapper benefitSetMapper;

    @Override
    public Object getHealth() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("timestamp", OffsetDateTime.now());
        return ApiResponse.success(health);
    }

    @Override
    public Object getMetrics() {
        // Basic operational metrics
        LambdaQueryWrapper<NfyaSubscribe> activeQuery = new LambdaQueryWrapper<>();
        activeQuery.eq(NfyaSubscribe::getStatus, "ACTIVE");
        long activeSubscriptions = subscribeMapper.selectCount(activeQuery);

        LambdaQueryWrapper<NfyaSubscribe> exhaustedQuery = new LambdaQueryWrapper<>();
        exhaustedQuery.eq(NfyaSubscribe::getStatus, "EXHAUSTED");
        long exhaustedSubscriptions = subscribeMapper.selectCount(exhaustedQuery);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("active_subscriptions", activeSubscriptions);
        metrics.put("exhausted_subscriptions", exhaustedSubscriptions);
        metrics.put("timestamp", OffsetDateTime.now());
        return ApiResponse.success(metrics);
    }

    @Override
    public Object postCacheEvict(PostCacheEvictRequest req) {
        // Cache eviction is handled by Redis; this is a no-op for local mode
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("evicted", true);
        result.put("cache_type", req.getCacheType() != null ? req.getCacheType() : "ALL");
        return ApiResponse.success(result);
    }

    @Override
    @Transactional
    public Object postJobsRefreshCycles(PostJobsRefreshCyclesRequest req) {
        boolean dryRun = req.getDryRun() != null && req.getDryRun();
        OffsetDateTime now = OffsetDateTime.now();
        int refreshedCount = 0;
        int expiredCount = 0;

        // 1. 查找需要刷新的订阅 (nextRefreshTime <= now 且 status in [ACTIVE, EXHAUSTED])
        LambdaQueryWrapper<NfyaSubscribe> refreshQuery = new LambdaQueryWrapper<>();
        refreshQuery.le(NfyaSubscribe::getNextRefreshTime, now)
                .in(NfyaSubscribe::getStatus, "ACTIVE", "EXHAUSTED");
        if (req.getTenantId() != null) {
            refreshQuery.eq(NfyaSubscribe::getTenantId, req.getTenantId());
        }
        List<NfyaSubscribe> toRefresh = subscribeMapper.selectList(refreshQuery);

        for (NfyaSubscribe sub : toRefresh) {
            // 检查是否已过期
            if (sub.getDateEnd() != null && sub.getDateEnd().isBefore(now)) {
                if (!dryRun) {
                    sub = subscribeMapper.selectById(sub.getId()); // re-read for version
                    if (sub == null) { expiredCount++; continue; }
                    LambdaUpdateWrapper<NfyaSubscribe> wrapper = new LambdaUpdateWrapper<>();
                    wrapper.eq(NfyaSubscribe::getId, sub.getId())
                            .eq(NfyaSubscribe::getVersion, sub.getVersion())
                            .set(NfyaSubscribe::getStatus, "EXPIRED")
                            .set(NfyaSubscribe::getVersion, sub.getVersion() + 1)
                            .set(NfyaSubscribe::getUpdatedAt, now);
                    subscribeMapper.update(null, wrapper);
                }
                expiredCount++;
                continue;
            }

            if (!dryRun) {
                // 跳过有冻结额度的订阅（存在未决的TCC预留）
                sub = subscribeMapper.selectById(sub.getId()); // re-read for latest state
                if (sub == null) continue;
                if (sub.getFrozenConsumed() != null && sub.getFrozenConsumed() > 0) {
                    continue;
                }

                // 刷新集合级周期
                NfyaNfySet set = benefitSetMapper.selectById(sub.getSetId());
                OffsetDateTime nextRefresh = set != null
                        ? calculateNextRefreshTime(now, set.getRefreshCycle(), set.getRefreshCycleUnit())
                        : null;

                LambdaUpdateWrapper<NfyaSubscribe> subWrapper = new LambdaUpdateWrapper<>();
                subWrapper.eq(NfyaSubscribe::getId, sub.getId())
                        .eq(NfyaSubscribe::getVersion, sub.getVersion())
                        .set(NfyaSubscribe::getPeriodConsumed, 0)
                        .set(NfyaSubscribe::getNextRefreshTime, nextRefresh)
                        .set(NfyaSubscribe::getStatus, "ACTIVE") // EXHAUSTED → ACTIVE on refresh
                        .set(NfyaSubscribe::getVersion, sub.getVersion() + 1)
                        .set(NfyaSubscribe::getUpdatedAt, now);
                subscribeMapper.update(null, subWrapper);

                // 刷新条目级周期
                LambdaQueryWrapper<NfyaSubscribeItem> itemQuery = new LambdaQueryWrapper<>();
                itemQuery.eq(NfyaSubscribeItem::getSubscribeId, sub.getId());
                List<NfyaSubscribeItem> items = subscribeItemMapper.selectList(itemQuery);
                for (NfyaSubscribeItem item : items) {
                    LambdaUpdateWrapper<NfyaSubscribeItem> itemWrapper = new LambdaUpdateWrapper<>();
                    itemWrapper.eq(NfyaSubscribeItem::getId, item.getId())
                            .eq(NfyaSubscribeItem::getVersion, item.getVersion())
                            .set(NfyaSubscribeItem::getPeriodConsumed, 0)
                            .set(NfyaSubscribeItem::getVersion, item.getVersion() + 1)
                            .set(NfyaSubscribeItem::getUpdatedAt, now);
                    subscribeItemMapper.update(null, itemWrapper);
                }
            }
            refreshedCount++;
        }

        // 桶过期观测 (V1.2.0 多源桶上线后): 候选查询已过滤 expires_at > now,
        // 但运维需要看到「有多少桶已过期」用于清理决策. 不改数据, 只统计.
        LambdaQueryWrapper<NfyaSubscribeItem> expiredBucketQuery = new LambdaQueryWrapper<>();
        expiredBucketQuery.isNotNull(NfyaSubscribeItem::getExpiresAt)
                .lt(NfyaSubscribeItem::getExpiresAt, now)
                .eq(NfyaSubscribeItem::getIsDeleted, 0);
        if (req.getTenantId() != null) {
            expiredBucketQuery.eq(NfyaSubscribeItem::getTenantId, req.getTenantId());
        }
        Long expiredBucketCount = subscribeItemMapper.selectCount(expiredBucketQuery);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("refreshed_count", refreshedCount);
        result.put("expired_count", expiredCount);
        result.put("expired_bucket_count", expiredBucketCount);
        result.put("dry_run", dryRun);
        return ApiResponse.success(result);
    }

    private OffsetDateTime calculateNextRefreshTime(OffsetDateTime begin, Integer cycle, String unit) {
        if (cycle == null || cycle == 0) return null;
        return switch (unit != null ? unit : "month") {
            case "hour" -> begin.plusHours(cycle);
            case "day" -> begin.plusDays(cycle);
            case "week" -> begin.plusWeeks(cycle);
            case "month" -> begin.plusMonths(cycle);
            case "year" -> begin.plusYears(cycle);
            default -> begin.plusMonths(cycle);
        };
    }
}
