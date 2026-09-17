package fun.commons.notification4j.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.dto.PatchAnnouncementsAnnouncementIdRequest;
import fun.commons.notification4j.dto.PostAnnouncementsRequest;
import fun.commons.notification4j.entity.NfyaAnnouncement;
import fun.commons.notification4j.entity.NfyaAnnouncementRead;
import fun.commons.notification4j.mapper.NfyaAnnouncementMapper;
import fun.commons.notification4j.mapper.NfyaAnnouncementReadMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 公告管理面（API-AAN-001~005，§4.3/§5.9.2；TENANT admin 面）。
 * 创建 → DRAFT（biz_no 幂等闸 10401；生效窗口非法 10102）；PATCH 限 DRAFT（10402）；
 * publish：草稿→发布，同租户同时生效 ≤20（10611）；offline 立即下线（重复/非发布态 10402）；
 * stats：read_count/confirm_count/confirms（Offset 分页）。
 * 外发任务生成（公共渠道 + 订阅 ANNOUNCEMENT 站外渠道）在第 6 步接线（接口文档 V1.0.6 已登记）。
 */
@Service
@RequiredArgsConstructor
public class AnnouncementAdminService {

    private static final int MAX_CONCURRENT_EFFECTIVE = 20;

    private final NfyaAnnouncementMapper announcementMapper;
    private final NfyaAnnouncementReadMapper readMapper;
    private final ObjectMapper objectMapper;
    private final DeliveryPlanService deliveryPlanService;

    /** API-AAN-001 POST 创建（DRAFT） */
    @Transactional
    public Map<String, Object> create(long tenantId, PostAnnouncementsRequest req) {
        NfyaAnnouncement a = buildDraft(tenantId, "TENANT", req, req.channelIds());
        try {
            announcementMapper.insert(a);
        } catch (DuplicateKeyException e) {
            throw new ApiException(10401, "业务号重复，公告已存在");
        }
        return Map.of("announcement_id", String.valueOf(a.getId()), "status", "DRAFT");
    }

    /**
     * API-PAN-001 平台公告创建（tenant_id=0 + scope=PLATFORM）。
     * channel_ids 强制清空（§4.4：平台域无渠道资源，站外仅走用户订阅 ANNOUNCEMENT 路径，
     * 外发展开见 DeliveryPlanService.planForAnnouncement 的订阅分支）。
     */
    @Transactional
    public Map<String, Object> createPlatform(PostAnnouncementsRequest req) {
        NfyaAnnouncement a = buildDraft(0L, "PLATFORM", req, List.of());
        try {
            announcementMapper.insert(a);
        } catch (DuplicateKeyException e) {
            throw new ApiException(10401, "业务号重复，公告已存在");
        }
        return Map.of("announcement_id", String.valueOf(a.getId()), "status", "DRAFT");
    }

    private NfyaAnnouncement buildDraft(long tenantId, String scope, PostAnnouncementsRequest req,
                                        List<String> channelIds) {
        OffsetDateTime eff = toTime(req.effectiveAt(), "effective_at");
        OffsetDateTime exp = toTime(req.expireAt(), "expire_at");
        if (!exp.isAfter(eff)) {
            throw new ApiException(10102, "生效窗口非法：expire_at 必须晚于 effective_at");
        }
        int needConfirm = req.needConfirm() == null ? 0 : req.needConfirm();
        if (needConfirm != 0 && needConfirm != 1) {
            throw new ApiException(10100, "need_confirm 仅允许 0/1");
        }
        NfyaAnnouncement a = new NfyaAnnouncement();
        a.setTenantId(tenantId);
        // 与消息域对齐：空白 biz_no 一律归 ""（放弃幂等豁免，uk 谓词 biz_no <> '' 不拦截）
        a.setBizNo(req.bizNo() == null || req.bizNo().isBlank() ? "" : req.bizNo().trim());
        a.setScope(scope);
        a.setTitle(req.title());
        a.setContent(req.content());
        a.setLevel(req.level() == null ? "IMPORTANT" : req.level());
        a.setNeedConfirm(needConfirm);
        a.setLinkUrl(req.linkUrl() == null ? "" : req.linkUrl());
        a.setChannelIds(toJson(channelIds == null ? List.of() : channelIds));
        a.setStatus("DRAFT");
        a.setEffectiveAt(eff);
        a.setExpireAt(exp);
        a.setConfirmCount(0);
        a.setExt("{}");
        return a;
    }

    /** API-AAN-001 GET 列表（status 可选过滤，Offset 分页，updated_at 倒序；total 与过滤谓词同口径） */
    public Map<String, Object> list(long tenantId, String status, Integer offset, Integer limit) {
        int n = (limit == null || limit <= 0) ? 20 : Math.min(limit, 100);
        int off = (offset == null || offset < 0) ? 0 : offset;
        if (status != null && !status.isBlank()
                && !Set.of("DRAFT", "PUBLISHED", "OFFLINE", "EXPIRED").contains(status)) {
            throw new ApiException(10100, "status 枚举非法(DRAFT/PUBLISHED/OFFLINE/EXPIRED)");
        }
        LambdaQueryWrapper<NfyaAnnouncement> qw = new LambdaQueryWrapper<NfyaAnnouncement>()
                .eq(NfyaAnnouncement::getTenantId, tenantId)
                .orderByDesc(NfyaAnnouncement::getUpdatedAt);
        if (status != null && !status.isBlank()) {
            qw.eq(NfyaAnnouncement::getStatus, status);
        }
        List<NfyaAnnouncement> rows = announcementMapper.selectList(qw.last("LIMIT " + n + " OFFSET " + off));
        Long total = announcementMapper.selectCount(new LambdaQueryWrapper<NfyaAnnouncement>()
                .eq(NfyaAnnouncement::getTenantId, tenantId)
                .eq(status != null && !status.isBlank(), NfyaAnnouncement::getStatus, status));
        List<Map<String, Object>> items = rows.stream().map(this::toVo).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", items);
        data.put("total", total);
        return data;
    }

    /** API-AAN-002 GET 详情（全字段） */
    public Map<String, Object> detail(long tenantId, String announcementId) {
        return toVo(requireOwned(tenantId, announcementId));
    }

    /** API-AAN-002 PATCH 修改（限 DRAFT，10402） */
    @Transactional
    public Map<String, Object> patch(long tenantId, String announcementId,
                                     PatchAnnouncementsAnnouncementIdRequest req) {
        NfyaAnnouncement a = requireOwned(tenantId, announcementId);
        if (!"DRAFT".equals(a.getStatus())) {
            throw new ApiException(10402, "非 DRAFT 态不可修改");
        }
        if (req.title() != null) a.setTitle(req.title());
        if (req.content() != null) a.setContent(req.content());
        if (req.level() != null) a.setLevel(req.level());
        if (req.needConfirm() != null) {
            if (req.needConfirm() != 0 && req.needConfirm() != 1) {
                throw new ApiException(10100, "need_confirm 仅允许 0/1");
            }
            a.setNeedConfirm(req.needConfirm());
        }
        if (req.linkUrl() != null) a.setLinkUrl(req.linkUrl());
        if (req.channelIds() != null) a.setChannelIds(toJson(req.channelIds()));
        if (req.effectiveAt() != null) a.setEffectiveAt(toTime(req.effectiveAt(), "effective_at"));
        if (req.expireAt() != null) a.setExpireAt(toTime(req.expireAt(), "expire_at"));
        if (a.getExpireAt() == null || a.getEffectiveAt() == null || !a.getExpireAt().isAfter(a.getEffectiveAt())) {
            throw new ApiException(10102, "生效窗口非法：expire_at 必须晚于 effective_at");
        }
        // 条件更新防 TOFU（评审第 5 步 P2）：加载后被人并发发布/下线则拒绝
        int affected = announcementMapper.update(a, new LambdaUpdateWrapper<NfyaAnnouncement>()
                .eq(NfyaAnnouncement::getId, a.getId())
                .eq(NfyaAnnouncement::getStatus, "DRAFT"));
        if (affected == 0) {
            throw new ApiException(10402, "非 DRAFT 态不可修改");
        }
        return Map.of("announcement_id", announcementId, "status", "DRAFT");
    }

    /**
     * API-AAN-003 发布（草稿→发布；未失效已发布 ≤20 → 10611）。
     * 10611 口径（评审第 5 步 P1）：统计 status=PUBLISHED 且 expire_at>now——未来生效窗口的
     * 已发布公告同样占名额，防止「先囤未来公告、到期同时生效」合法击穿 20 上限；
     * 并发双发布的软限竞态登记为已知边界（接口文档 V1.0.6 ⑨）。
     */
    @Transactional
    public Map<String, Object> publish(long tenantId, String announcementId) {
        NfyaAnnouncement a = requireOwned(tenantId, announcementId);
        if (!"DRAFT".equals(a.getStatus())) {
            throw new ApiException(10402, "非草稿态不可发布");
        }
        OffsetDateTime now = OffsetDateTime.now();
        Long effective = announcementMapper.selectCount(new LambdaQueryWrapper<NfyaAnnouncement>()
                .eq(NfyaAnnouncement::getTenantId, tenantId)
                .eq(NfyaAnnouncement::getStatus, "PUBLISHED")
                .gt(NfyaAnnouncement::getExpireAt, now));
        if (effective != null && effective >= MAX_CONCURRENT_EFFECTIVE) {
            throw new ApiException(10611, "同时生效公告不可超过20条，请先下线旧公告");
        }
        a.setStatus("PUBLISHED");
        a.setPublishedAt(now);
        // 条件更新防并发改态（与 patch TOFU 同因）
        int affected = announcementMapper.update(a, new LambdaUpdateWrapper<NfyaAnnouncement>()
                .eq(NfyaAnnouncement::getId, a.getId())
                .eq(NfyaAnnouncement::getStatus, "DRAFT"));
        if (affected == 0) {
            throw new ApiException(10402, "非草稿态不可发布");
        }
        // 外发任务生成：勾选公共渠道 + 订阅 ANNOUNCEMENT 用户的站外渠道（同事务；引擎第 6b 步消费）
        int planned = deliveryPlanService.planForAnnouncement(a);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("announcement_id", announcementId);
        data.put("status", "PUBLISHED");
        data.put("delivery_planned", planned);
        return data;
    }

    /** API-AAN-004 下线（立即对用户侧不可见；非发布态 10402；条件更新防并发改态） */
    @Transactional
    public Map<String, Object> offline(long tenantId, String announcementId) {
        NfyaAnnouncement a = requireOwned(tenantId, announcementId);
        if (!"PUBLISHED".equals(a.getStatus())) {
            throw new ApiException(10402, "已下线或未发布");
        }
        a.setStatus("OFFLINE");
        int affected = announcementMapper.update(a, new LambdaUpdateWrapper<NfyaAnnouncement>()
                .eq(NfyaAnnouncement::getId, a.getId())
                .eq(NfyaAnnouncement::getStatus, "PUBLISHED"));
        if (affected == 0) {
            throw new ApiException(10402, "已下线或未发布");
        }
        return Map.of("announcement_id", announcementId, "status", "OFFLINE");
    }

    /** API-AAN-005 统计：read_count/confirm_count/confirms（Offset 分页） */
    public Map<String, Object> stats(long tenantId, String announcementId, Integer offset, Integer limit) {
        NfyaAnnouncement a = requireOwned(tenantId, announcementId);
        int n = (limit == null || limit <= 0) ? 20 : Math.min(limit, 100);
        int off = (offset == null || offset < 0) ? 0 : offset;
        Long readCount = readMapper.selectCount(new LambdaQueryWrapper<NfyaAnnouncementRead>()
                .eq(NfyaAnnouncementRead::getTenantId, tenantId)
                .eq(NfyaAnnouncementRead::getAnnouncementId, a.getId()));
        List<NfyaAnnouncementRead> confirms = readMapper.selectList(new LambdaQueryWrapper<NfyaAnnouncementRead>()
                .eq(NfyaAnnouncementRead::getTenantId, tenantId)
                .eq(NfyaAnnouncementRead::getAnnouncementId, a.getId())
                .isNotNull(NfyaAnnouncementRead::getConfirmAt)
                .orderByDesc(NfyaAnnouncementRead::getConfirmAt)
                .last("LIMIT " + n + " OFFSET " + off));
        Long confirmCount = readMapper.selectCount(new LambdaQueryWrapper<NfyaAnnouncementRead>()
                .eq(NfyaAnnouncementRead::getTenantId, tenantId)
                .eq(NfyaAnnouncementRead::getAnnouncementId, a.getId())
                .isNotNull(NfyaAnnouncementRead::getConfirmAt));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("read_count", readCount);
        data.put("confirm_count", confirmCount);
        data.put("confirms", confirms.stream().map(r -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userid", r.getUserid());
            item.put("confirm_at", r.getConfirmAt() == null ? null : r.getConfirmAt().toInstant().toEpochMilli());
            return item;
        }).toList());
        return data;
    }

    /** 归属校验：跨租户/不存在统一 10400（防探测） */
    private NfyaAnnouncement requireOwned(long tenantId, String announcementId) {
        long id;
        try {
            id = Long.parseLong(announcementId);
        } catch (NumberFormatException e) {
            throw new ApiException(10400, "公告不存在");
        }
        NfyaAnnouncement a = announcementMapper.selectById(id);
        if (a == null || a.getTenantId() != tenantId) {
            throw new ApiException(10400, "公告不存在");
        }
        return a;
    }

    private OffsetDateTime toTime(Long epochMillis, String field) {
        if (epochMillis == null) {
            throw new ApiException(10101, field + " 缺失");
        }
        return OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
    }

    private String toJson(List<String> ids) {
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            throw new IllegalStateException("channel_ids 序列化失败", e);
        }
    }

    private Map<String, Object> toVo(NfyaAnnouncement a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("announcement_id", String.valueOf(a.getId()));
        m.put("title", a.getTitle());
        m.put("content", a.getContent());
        m.put("level", a.getLevel());
        m.put("need_confirm", a.getNeedConfirm());
        m.put("link_url", a.getLinkUrl());
        m.put("channel_ids", parseIds(a.getChannelIds()));
        m.put("status", a.getStatus());
        m.put("effective_at", a.getEffectiveAt() == null ? null : a.getEffectiveAt().toInstant().toEpochMilli());
        m.put("expire_at", a.getExpireAt() == null ? null : a.getExpireAt().toInstant().toEpochMilli());
        m.put("published_at", a.getPublishedAt() == null ? null : a.getPublishedAt().toInstant().toEpochMilli());
        // 冗余列仅落库展示口径，对外以回执实数为准（评审第 5 步 P2：防双口径漂移）
        m.put("confirm_count", readMapper.selectCount(new LambdaQueryWrapper<NfyaAnnouncementRead>()
                .eq(NfyaAnnouncementRead::getTenantId, a.getTenantId())
                .eq(NfyaAnnouncementRead::getAnnouncementId, a.getId())
                .isNotNull(NfyaAnnouncementRead::getConfirmAt)));
        m.put("created_at", a.getCreatedAt() == null ? null : a.getCreatedAt().toInstant().toEpochMilli());
        return m;
    }

    private List<String> parseIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }
}
