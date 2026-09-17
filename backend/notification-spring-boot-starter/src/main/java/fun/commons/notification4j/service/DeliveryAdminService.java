package fun.commons.notification4j.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.entity.NfyaDelivery;
import fun.commons.notification4j.mapper.NfyaDeliveryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 投递记录查询与人工重投（API-DLV-001/002，§5.9.2）。
 * DLV-001：biz_no/userid/channel_type/status/created_after/created_before 过滤（Offset 分页），
 * target 快照已脱敏，直接输出；biz_no 过滤经参数化子查询定位 message。
 * DLV-002：仅 DEAD 可重投（→PENDING、next_retry_at=now），非 DEAD 10402；跨租户 10400。
 */
@Service
@RequiredArgsConstructor
public class DeliveryAdminService {

    private final NfyaDeliveryMapper deliveryMapper;

    public Map<String, Object> list(long tenantId, String bizNo, String userid, String channelType,
                                    String status, Long createdAfter, Long createdBefore,
                                    Integer offset, Integer limit) {
        int n = (limit == null || limit <= 0) ? 20 : Math.min(limit, 100);
        int off = (offset == null || offset < 0) ? 0 : offset;
        LambdaQueryWrapper<NfyaDelivery> qw = new LambdaQueryWrapper<NfyaDelivery>()
                .eq(NfyaDelivery::getTenantId, tenantId)
                .orderByDesc(NfyaDelivery::getCreatedAt)
                .orderByDesc(NfyaDelivery::getId);
        if (bizNo != null && !bizNo.isBlank()) {
            qw.apply("source_id IN (SELECT id FROM nfya_message WHERE tenant_id = {0} AND biz_no = {1})",
                    tenantId, bizNo);
        }
        if (userid != null && !userid.isBlank()) qw.eq(NfyaDelivery::getUserid, userid);
        if (channelType != null && !channelType.isBlank()) qw.eq(NfyaDelivery::getChannelType, channelType);
        if (status != null && !status.isBlank()) qw.eq(NfyaDelivery::getStatus, status);
        if (createdAfter != null) qw.ge(NfyaDelivery::getCreatedAt, toTime(createdAfter));
        if (createdBefore != null) qw.le(NfyaDelivery::getCreatedAt, toTime(createdBefore));
        List<NfyaDelivery> rows = deliveryMapper.selectList(qw.last("LIMIT " + n + " OFFSET " + off));
        Long total = deliveryMapper.selectCount(new LambdaQueryWrapper<NfyaDelivery>()
                .eq(NfyaDelivery::getTenantId, tenantId));
        List<Map<String, Object>> items = rows.stream().map(this::toVo).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", items);
        data.put("total", total);
        return data;
    }

    /** DLV-002 人工重投：仅 DEAD（10402）；重置 PENDING + 立即可扫 */
    @Transactional
    public Map<String, Object> retry(long tenantId, String deliveryId) {
        long id;
        try {
            id = Long.parseLong(deliveryId);
        } catch (NumberFormatException e) {
            throw new ApiException(10400, "投递记录不存在");
        }
        NfyaDelivery d = deliveryMapper.selectById(id);
        if (d == null || d.getTenantId() != tenantId) {
            throw new ApiException(10400, "投递记录不存在");
        }
        if (!"DEAD".equals(d.getStatus())) {
            throw new ApiException(10402, "非 DEAD 状态不可重投");
        }
        d.setStatus("PENDING");
        d.setNextRetryAt(OffsetDateTime.now());
        deliveryMapper.updateById(d);
        return Map.of("delivery_id", deliveryId, "status", "PENDING");
    }

    private OffsetDateTime toTime(Long epochMillis) {
        return OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
    }

    private Map<String, Object> toVo(NfyaDelivery d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("delivery_id", String.valueOf(d.getId()));
        m.put("source_type", d.getSourceType());
        m.put("source_id", String.valueOf(d.getSourceId()));
        m.put("userid", d.getUserid());
        m.put("channel_id", String.valueOf(d.getChannelId()));
        m.put("channel_type", d.getChannelType());
        m.put("target", d.getTarget()); // 快照已脱敏
        m.put("title", d.getTitle());
        m.put("status", d.getStatus());
        m.put("retry_count", d.getRetryCount());
        m.put("next_retry_at", d.getNextRetryAt() == null ? null : d.getNextRetryAt().toInstant().toEpochMilli());
        m.put("error_message", d.getErrorMessage());
        m.put("sent_at", d.getSentAt() == null ? null : d.getSentAt().toInstant().toEpochMilli());
        m.put("created_at", d.getCreatedAt() == null ? null : d.getCreatedAt().toInstant().toEpochMilli());
        return m;
    }
}
