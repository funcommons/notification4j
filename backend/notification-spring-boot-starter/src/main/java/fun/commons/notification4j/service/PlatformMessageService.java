package fun.commons.notification4j.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.entity.NfyaMessage;
import fun.commons.notification4j.mapper.NfyaMessageRecipientMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台维度消息查询（API-PPM 平台站内信；§4.4 平台域只读面）。
 * 数据走既有 MessageService（runtime 面同源 mapper/list 路径），本类只做平台维度
 * 「跨租户无 tenant 谓词」的查询包装——不复制发送/定时任务逻辑（发送面仍归 MSG-001）。
 * 分页沿平台公告口径（offset/limit，limit 默认 20 上限 100）；时间筛选/回显统一 epoch 毫秒。
 */
@Service
@RequiredArgsConstructor
public class PlatformMessageService {

    private final MessageService messageService;
    private final NfyaMessageRecipientMapper recipientMapper;

    /**
     * API-PPM-001 平台全量消息列表（跨租户）。
     * user_id 经收件人表参数化子查询过滤（回执表带 userid 索引，防注入 + 语义=「发给该用户的消息」）。
     */
    public Map<String, Object> list(String userid, String typeCode, Long createdFrom, Long createdTo,
                                    String keyword, Integer offset, Integer limit) {
        int n = (limit == null || limit <= 0) ? 20 : Math.min(limit, 100);
        int off = (offset == null || offset < 0) ? 0 : offset;
        LambdaQueryWrapper<NfyaMessage> listQw = baseWrapper(userid, typeCode, createdFrom, createdTo, keyword)
                .orderByDesc(NfyaMessage::getId)
                .last("LIMIT " + n + " OFFSET " + off);
        List<NfyaMessage> rows = messageService.list(listQw);
        Long total = messageService.count(baseWrapper(userid, typeCode, createdFrom, createdTo, keyword));
        List<Map<String, Object>> items = new ArrayList<>();
        for (NfyaMessage m : rows) {
            items.add(toListItem(m));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", items);
        data.put("total", total);
        return data;
    }

    /**
     * API-PPM-002 消息详情（全字段 + 已读回执统计；跨租户属主校验退化为存在性——平台面全域可见）。
     * 不存在/非数字 id 一律 10400（口径同 MSG-005 防探测）。
     */
    public Map<String, Object> detail(String messageId) {
        long id;
        try {
            id = Long.parseLong(messageId);
        } catch (NumberFormatException e) {
            throw new ApiException(10400, "资源不存在或无权访问");
        }
        NfyaMessage m = messageService.getById(id);
        if (m == null) {
            throw new ApiException(10400, "资源不存在或无权访问");
        }
        Map<String, Object> data = toListItem(m);
        data.put("biz_no", m.getBizNo());
        data.put("content", m.getContent());
        data.put("link_url", m.getLinkUrl());
        data.put("sender", m.getSender());
        Long readCount = recipientMapper.selectCount(new LambdaQueryWrapper<fun.commons.notification4j.entity.NfyaMessageRecipient>()
                .eq(fun.commons.notification4j.entity.NfyaMessageRecipient::getMessageId, id)
                .eq(fun.commons.notification4j.entity.NfyaMessageRecipient::getReadStatus, "READ"));
        data.put("read_count", readCount == null ? 0 : readCount);
        return data;
    }

    /** 公共筛选段（list 与 count 各建一次——orderBy/last 分页段不得进入 count 聚合查询） */
    private LambdaQueryWrapper<NfyaMessage> baseWrapper(String userid, String typeCode,
                                                        Long createdFrom, Long createdTo, String keyword) {
        LambdaQueryWrapper<NfyaMessage> qw = new LambdaQueryWrapper<NfyaMessage>();
        if (userid != null && !userid.isBlank()) {
            qw.apply("id IN (SELECT message_id FROM nfya_message_recipient WHERE userid = {0})", userid);
        }
        if (typeCode != null && !typeCode.isBlank()) {
            qw.eq(NfyaMessage::getTypeCode, typeCode);
        }
        if (createdFrom != null) {
            qw.ge(NfyaMessage::getCreatedAt, millisToOffset(createdFrom));
        }
        if (createdTo != null) {
            qw.le(NfyaMessage::getCreatedAt, millisToOffset(createdTo));
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(NfyaMessage::getTitle, keyword)
                    .or()
                    .like(NfyaMessage::getContent, keyword));
        }
        return qw;
    }

    /** 列表项字段（detail 复用为基底，全量字段追加在后） */
    private Map<String, Object> toListItem(NfyaMessage m) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("message_id", String.valueOf(m.getId()));
        item.put("tenant_id", m.getTenantId());
        item.put("type_code", m.getTypeCode());
        item.put("level", m.getLevel());
        item.put("title", m.getTitle());
        item.put("status", m.getStatus());
        item.put("receiver_count", m.getReceiverCount());
        item.put("created_at", m.getCreatedAt() == null ? null : m.getCreatedAt().toInstant().toEpochMilli());
        return item;
    }

    private OffsetDateTime millisToOffset(long millis) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }
}
