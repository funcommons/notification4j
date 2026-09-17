package fun.commons.notification4j.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.dto.PostMessagesRequest;
import fun.commons.notification4j.entity.NfyaDelivery;
import fun.commons.notification4j.entity.NfyaMessage;
import fun.commons.notification4j.entity.NfyaMessageRecipient;
import fun.commons.notification4j.mapper.NfyaMessageMapper;
import fun.commons.notification4j.mapper.NfyaMessageRecipientMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 消息发送与站内信读侧（F-MSG-001/003/002）。
 * 契约：biz_no 幂等真闸（10401）；站内同步落库、外发异步（V1.0 外发引擎后续步骤）；
 * 发送 = 一条 message + 批量 INSERT recipient（§5.2 批量同步豁免论证）；
 * 未读数走部分索引；列表 Keyset 分页（Cursor = base64(created_at 毫秒, id)）。
 */
@Service
@RequiredArgsConstructor
public class MessageService extends ServiceImpl<NfyaMessageMapper, NfyaMessage> {

    private static final int MARK_READ_BATCH = 500;

    private final NfyaMessageRecipientMapper recipientMapper;
    private final MessageTypeService messageTypeService;
    private final DeliveryPlanService deliveryPlanService;
    private final fun.commons.notification4j.mapper.NfyaDeliveryMapper deliveryMapper;

    @Transactional
    public Map<String, Object> send(long tenantId, PostMessagesRequest req) {
        messageTypeService.requireEnabled(tenantId, req.typeCode());
        if (req.userIds() == null || req.userIds().isEmpty()) {
            throw new ApiException(10101, "接收人列表不能为空");
        }
        List<String> userIds = req.userIds().stream().distinct().toList();
        if (userIds.size() > 1000) {
            throw new ApiException(10102, "接收人数量超限(单次≤1000)");
        }
        NfyaMessage m = new NfyaMessage();
        m.setTenantId(tenantId);
        m.setBizNo(req.bizNo() == null || req.bizNo().isBlank()
                ? UUID.randomUUID().toString().replace("-", "") : req.bizNo()); // 去横线小写，与 BatchJobService 口径一致
        m.setTypeCode(req.typeCode());
        m.setLevel(req.level() == null ? "NORMAL" : req.level());
        m.setTitle(req.title());
        m.setContent(req.content());
        m.setLinkUrl(req.linkUrl() == null ? "" : req.linkUrl());
        m.setTemplateId(0L);
        m.setParams("{}");
        m.setReceiverCount(userIds.size());
        m.setStatus("SENT");
        m.setSender("API");
        m.setExt("{}");
        try {
            save(m);
        } catch (DuplicateKeyException e) {
            throw new ApiException(10401, "业务号重复，幂等拒绝");
        }
        // 批量插入接收人（评审第 3 步 P1：逐条 insert 违背 §5.2 批量论证）
        List<NfyaMessageRecipient> recipients = new ArrayList<>(userIds.size());
        for (String uid : userIds) {
            NfyaMessageRecipient r = new NfyaMessageRecipient();
            r.setTenantId(tenantId);
            r.setMessageId(m.getId());
            r.setUserid(uid);
            r.setReadStatus("UNREAD");
            r.setExt("{}");
            recipients.add(r);
        }
        Db.saveBatch(recipients);
        // 投递计划：订阅矩阵展开 → nfya_delivery PENDING 行（引擎异步消费，第 6b 步）
        int planned = deliveryPlanService.planForMessage(
                tenantId, m.getId(), m.getTypeCode(), m.getLevel(), m.getTitle(), userIds);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message_id", String.valueOf(m.getId()));
        data.put("biz_no", m.getBizNo());
        data.put("receiver_count", userIds.size());
        data.put("inapp_saved", true);
        data.put("delivery_planned", planned);
        return data;
    }

    /** 站内信未读数（部分索引 idx_nfya_recipient_unread；公告未确认数由 AnnouncementService 提供，控制器合成）；撤回消息不计入 */
    public long unreadInappCount(long tenantId, String userid) {
        Long n = recipientMapper.selectCount(new LambdaQueryWrapper<NfyaMessageRecipient>()
                .eq(NfyaMessageRecipient::getTenantId, tenantId)
                .eq(NfyaMessageRecipient::getUserid, userid)
                .eq(NfyaMessageRecipient::getReadStatus, "UNREAD")
                .apply("message_id NOT IN (SELECT id FROM nfya_message WHERE tenant_id = {0} AND status = 'CANCELLED')",
                        tenantId));
        return n == null ? 0 : n;
    }

    /**
     * MSG-003 按 biz_no 查发送结果与投递（T 鉴权；跨租户/不存在 10400 防探测）。
     */
    public Map<String, Object> sendResults(long tenantId, String bizNo) {
        NfyaMessage m = getOne(new LambdaQueryWrapper<NfyaMessage>()
                .eq(NfyaMessage::getTenantId, tenantId)
                .eq(NfyaMessage::getBizNo, bizNo)
                .last("LIMIT 1"));
        if (m == null) {
            throw new ApiException(10400, "资源不存在或无权访问");
        }
        List<NfyaMessageRecipient> recipients = recipientMapper.selectList(new LambdaQueryWrapper<NfyaMessageRecipient>()
                .eq(NfyaMessageRecipient::getTenantId, tenantId)
                .eq(NfyaMessageRecipient::getMessageId, m.getId()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message_id", String.valueOf(m.getId()));
        data.put("biz_no", m.getBizNo());
        data.put("type_code", m.getTypeCode());
        data.put("status", m.getStatus());
        data.put("receiver_count", m.getReceiverCount());
        data.put("read_count", recipients.stream().filter(r -> "READ".equals(r.getReadStatus())).count());
        data.put("created_at", m.getCreatedAt() == null ? null : m.getCreatedAt().toInstant().toEpochMilli());
        List<fun.commons.notification4j.entity.NfyaDelivery> deliveries =
                deliveryMapper.selectList(new LambdaQueryWrapper<fun.commons.notification4j.entity.NfyaDelivery>()
                        .eq(fun.commons.notification4j.entity.NfyaDelivery::getTenantId, tenantId)
                        .eq(fun.commons.notification4j.entity.NfyaDelivery::getSourceType, "MESSAGE")
                        .eq(fun.commons.notification4j.entity.NfyaDelivery::getSourceId, m.getId()));
        data.put("deliveries", deliveries.stream().map(d -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userid", d.getUserid());
            item.put("channel_type", d.getChannelType());
            item.put("status", d.getStatus());
            item.put("sent_at", d.getSentAt() == null ? null : d.getSentAt().toInstant().toEpochMilli());
            item.put("error_message", d.getErrorMessage());
            return item;
        }).toList());
        return data;
    }

    /**
     * MSG-009 消息撤回（V1.2；T 鉴权发送方业务系统行为）：
     * ① 属主校验——不存在/跨租户/非数字 id 一律 10400 同文案「消息不存在或无权访问」（防探测，§6.3）；
     * ② 状态机 SENT→CANCELLED（条件 UPDATE 带 status='SENT' 前置）；已 CANCELLED 再撤=幂等成功（0 条拦截）；
     * ③ 级联外发拦截——nfya_delivery 仅 PENDING CAS 置 CANCELLED（带 status='PENDING' 前置，
     *    防与 DeliveryEngine claim(SENDING)/回写(SUCCESS) 竞态；已在线上的不追回，at-least-once）；
     * ④ biz_no 幂等记录不动（同 biz_no 重发仍 10401；send-results 保持返回撤回消息，运营排查口径）。
     */
    @Transactional
    public Map<String, Object> cancel(long tenantId, String messageId) {
        long id;
        try {
            id = Long.parseLong(messageId);
        } catch (NumberFormatException e) {
            throw new ApiException(10400, "消息不存在或无权访问");
        }
        NfyaMessage m = getById(id);
        if (m == null || m.getTenantId() == null || m.getTenantId().longValue() != tenantId) {
            throw new ApiException(10400, "消息不存在或无权访问");
        }
        int updated = baseMapper.update(null, new LambdaUpdateWrapper<NfyaMessage>()
                .eq(NfyaMessage::getId, id)
                .eq(NfyaMessage::getTenantId, tenantId)
                .eq(NfyaMessage::getStatus, "SENT")
                .set(NfyaMessage::getStatus, "CANCELLED"));
        int cancelledDeliveries = 0;
        if (updated > 0) {
            cancelledDeliveries = deliveryMapper.update(null, new LambdaUpdateWrapper<NfyaDelivery>()
                    .eq(NfyaDelivery::getTenantId, tenantId)
                    .eq(NfyaDelivery::getSourceType, "MESSAGE")
                    .eq(NfyaDelivery::getSourceId, id)
                    .eq(NfyaDelivery::getStatus, "PENDING")
                    .set(NfyaDelivery::getStatus, "CANCELLED"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message_id", String.valueOf(id));
        data.put("status", "CANCELLED");
        data.put("cancelled_deliveries", cancelledDeliveries);
        return data;
    }

    /** MSG-005 消息详情（T+U；返回即置已读——置已读与 MSG-006 同口径，幂等）；非本人 10400 */
    @Transactional
    public Map<String, Object> detailAndMarkRead(long tenantId, String userid, String messageId) {
        long id;
        try {
            id = Long.parseLong(messageId);
        } catch (NumberFormatException e) {
            throw new ApiException(10400, "资源不存在或无权访问");
        }
        NfyaMessage m = getById(id);
        NfyaMessageRecipient r = recipientMapper.selectOne(new LambdaQueryWrapper<NfyaMessageRecipient>()
                .eq(NfyaMessageRecipient::getTenantId, tenantId)
                .eq(NfyaMessageRecipient::getMessageId, id)
                .eq(NfyaMessageRecipient::getUserid, userid)
                .last("LIMIT 1"));
        if (m == null || r == null || "CANCELLED".equals(m.getStatus())) {
            // 撤回消息对用户侧不可见（MSG-009 V1.2），口径同不存在——10400 防探测
            throw new ApiException(10400, "资源不存在或无权访问");
        }
        if ("UNREAD".equals(r.getReadStatus())) {
            markRead(tenantId, userid, List.of(messageId), null);
            r.setReadStatus("READ");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message_id", messageId);
        data.put("title", m.getTitle());
        data.put("content", m.getContent());
        data.put("type_code", m.getTypeCode());
        data.put("level", m.getLevel());
        data.put("link_url", m.getLinkUrl());
        data.put("read_status", r.getReadStatus());
        data.put("created_at", m.getCreatedAt() == null ? null : m.getCreatedAt().toInstant().toEpochMilli());
        return data;
    }

    /** MSG-008 最近 N 条（铃铛下拉，limit≤10 默认 5；字段同 MSG-004 列表项） */
    public Map<String, Object> recent(long tenantId, String userid, Integer limit) {
        int n = (limit == null || limit <= 0) ? 5 : Math.min(limit, 10);
        return list(tenantId, userid, null, null, null, null, n);
    }

    /** 我的消息列表（Cursor 分页；type_code/level 经参数化子查询过滤——防注入 + 带 tenant 谓词可命中索引） */
    public Map<String, Object> list(long tenantId, String userid, String typeCode, String level,
                                    String readStatus, String cursor, Integer limit) {
        int n = (limit == null || limit <= 0) ? 20 : Math.min(limit, 50);
        LambdaQueryWrapper<NfyaMessageRecipient> qw = new LambdaQueryWrapper<NfyaMessageRecipient>()
                .eq(NfyaMessageRecipient::getTenantId, tenantId)
                .eq(NfyaMessageRecipient::getUserid, userid);
        // MSG-009 撤回消息用户侧不可见（SQL 内过滤保证分页/cursor 口径一致；子查询走 nfya_message 主键）
        qw.apply("message_id NOT IN (SELECT id FROM nfya_message WHERE tenant_id = {0} AND status = 'CANCELLED')",
                tenantId);
        if (typeCode != null && !typeCode.isBlank()) {
            qw.apply("message_id IN (SELECT id FROM nfya_message WHERE tenant_id = {0} AND type_code = {1} AND is_deleted = 0)",
                    tenantId, typeCode);
        }
        if (level != null && !level.isBlank()) {
            qw.apply("message_id IN (SELECT id FROM nfya_message WHERE tenant_id = {0} AND level = {1} AND is_deleted = 0)",
                    tenantId, level);
        }
        if (readStatus != null && !readStatus.isBlank()) qw.eq(NfyaMessageRecipient::getReadStatus, readStatus);
        applyCursor(qw, cursor);
        qw.orderByDesc(NfyaMessageRecipient::getCreatedAt).orderByDesc(NfyaMessageRecipient::getId);
        List<NfyaMessageRecipient> rows = recipientMapper.selectList(qw.last("LIMIT " + (n + 1)));
        boolean hasMore = rows.size() > n;
        List<NfyaMessageRecipient> page = hasMore ? rows.subList(0, n) : rows;

        // 一次取回消息主档，消除逐行 getById 的 N+1（评审第 3 步 P2）
        Map<Long, NfyaMessage> messages = page.isEmpty() ? Map.of()
                : listByIds(page.stream().map(NfyaMessageRecipient::getMessageId).toList()).stream()
                        .collect(Collectors.toMap(NfyaMessage::getId, Function.identity()));

        List<Map<String, Object>> items = new ArrayList<>();
        String nextCursor = null;
        for (NfyaMessageRecipient r : page) {
            NfyaMessage msg = messages.get(r.getMessageId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("message_id", String.valueOf(r.getMessageId()));
            item.put("title", msg == null ? "" : msg.getTitle());
            item.put("type_code", msg == null ? "" : msg.getTypeCode());
            item.put("level", msg == null ? "NORMAL" : msg.getLevel());
            item.put("read_status", r.getReadStatus());
            item.put("created_at", r.getCreatedAt() == null ? null : r.getCreatedAt().toInstant().toEpochMilli());
            items.add(item);
            nextCursor = encodeCursor(r.getCreatedAt(), r.getId());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", items);
        data.put("next_cursor", hasMore ? nextCursor : null);
        data.put("has_more", hasMore);
        return data;
    }

    /**
     * 批量已读（天然幂等）：ids ≤100 或 all=true 二选一（双传 10101）。
     * all=true 分批更新（每批 500，§5.5 契约），避免无上限单语句锁窗口。
     */
    @Transactional
    public Map<String, Object> markRead(long tenantId, String userid, List<String> messageIds, Boolean all) {
        boolean allFlag = Boolean.TRUE.equals(all);
        if (allFlag && messageIds != null && !messageIds.isEmpty()) {
            throw new ApiException(10101, "message_ids 与 all 只能二选一");
        }
        List<Long> ids = null;
        if (!allFlag) {
            if (messageIds == null || messageIds.isEmpty()) {
                throw new ApiException(10101, "message_ids 与 all 必须二选一");
            }
            if (messageIds.size() > 100) {
                throw new ApiException(10102, "单次已读数量超限(≤100)");
            }
            try {
                ids = messageIds.stream().map(Long::valueOf).toList();
            } catch (NumberFormatException e) {
                throw new ApiException(10102, "message_ids 含非法 id");
            }
        }
        int total = 0;
        if (ids != null) {
            // 用户传入的是 message_id（非回执行主键）
            total += recipientMapper.update(null, new LambdaUpdateWrapper<NfyaMessageRecipient>()
                    .eq(NfyaMessageRecipient::getTenantId, tenantId)
                    .eq(NfyaMessageRecipient::getUserid, userid)
                    .in(NfyaMessageRecipient::getMessageId, ids)
                    .eq(NfyaMessageRecipient::getReadStatus, "UNREAD")
                    .set(NfyaMessageRecipient::getReadStatus, "READ")
                    .set(NfyaMessageRecipient::getReadAt, OffsetDateTime.now()));
        } else {
            // all=true 分批：PG UPDATE 不支持 LIMIT，改为按主键分片（查批 → 按 id 集更新）
            while (true) {
                List<Long> batch = recipientMapper.selectList(new LambdaQueryWrapper<NfyaMessageRecipient>()
                        .select(NfyaMessageRecipient::getId)
                        .eq(NfyaMessageRecipient::getTenantId, tenantId)
                        .eq(NfyaMessageRecipient::getUserid, userid)
                        .eq(NfyaMessageRecipient::getReadStatus, "UNREAD")
                        .orderByAsc(NfyaMessageRecipient::getId)
                        .last("LIMIT " + MARK_READ_BATCH))
                        .stream().map(NfyaMessageRecipient::getId).toList();
                if (batch.isEmpty()) {
                    break;
                }
                total += recipientMapper.update(null, new LambdaUpdateWrapper<NfyaMessageRecipient>()
                        .eq(NfyaMessageRecipient::getTenantId, tenantId)
                        .eq(NfyaMessageRecipient::getUserid, userid)
                        .in(NfyaMessageRecipient::getId, batch)
                        .eq(NfyaMessageRecipient::getReadStatus, "UNREAD")
                        .set(NfyaMessageRecipient::getReadStatus, "READ")
                        .set(NfyaMessageRecipient::getReadAt, OffsetDateTime.now()));
                if (batch.size() < MARK_READ_BATCH) {
                    break;
                }
            }
        }
        return Map.of("read_count", total);
    }

    private void applyCursor(LambdaQueryWrapper<NfyaMessageRecipient> qw, String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return;
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("_");
            long millis = Long.parseLong(parts[0]);
            long id = Long.parseLong(parts[1]);
            OffsetDateTime c = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneOffset.UTC);
            qw.and(w -> w.lt(NfyaMessageRecipient::getCreatedAt, c)
                    .or(w2 -> w2.eq(NfyaMessageRecipient::getCreatedAt, c)
                            .lt(NfyaMessageRecipient::getId, id)));
        } catch (Exception e) {
            throw new ApiException(10102, "cursor 格式不正确");
        }
    }

    private String encodeCursor(OffsetDateTime createdAt, Long id) {
        if (createdAt == null) {
            return null;
        }
        String raw = createdAt.toInstant().toEpochMilli() + "_" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
