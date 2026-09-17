package fun.commons.notification4j.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.entity.NfyaAnnouncement;
import fun.commons.notification4j.entity.NfyaAnnouncementRead;
import fun.commons.notification4j.mapper.NfyaAnnouncementMapper;
import fun.commons.notification4j.mapper.NfyaAnnouncementReadMapper;
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

/**
 * 公告域 runtime 面（API-ANN-001/002/003，§5.6；fanout-on-read）。
 * 生效列表 = tenant_id IN (0, :tid) AND status=PUBLISHED AND now∈[effective_at, expire_at)，
 * published_at 倒序 + Keyset Cursor（同 §5.4）；my_status 由回执表反查（CONFIRMED/READ/NONE）。
 * 标记阅读/确认幂等（uk_nfya_ann_read_user 兜底）；确认原子 +1 confirm_count。
 * 回执行归属域 = 公告归属租户（nfya_announcement_read.tenant_id = a.tenantId，第 28 步口径修复）：
 * 租户公告等价调用方域；平台公告回执归 0 域，与 admin/PAN 侧 detail/stats 的 confirm_count
 * （按 a.tenantId 数回执）自洽——修前租户用户确认平台公告按调用方租户落库，PAN 口径恒 0。
 * 用户侧读状态查询（my_status/unconfirmedCount）的回执域谓词同步为 tenant_id IN (0, :tid)
 * （任一可见公告的回执域必属此二域，语义与逐公告按 a.tenantId 等值过滤严格等价）。
 * 错误口径：10400 公告不存在/未生效（含跨租户防探测）；10402 公告已失效。
 * 管理面（AAN-001~005）在第 5b 步；外发任务生成在第 6 步（发布时按 channel_ids+订阅矩阵）。
 */
@Service
@RequiredArgsConstructor
public class AnnouncementService extends ServiceImpl<NfyaAnnouncementMapper, NfyaAnnouncement> {

    private final NfyaAnnouncementReadMapper readMapper;

    /** API-ANN-001 生效公告列表（平台 + 本租户合并） */
    public Map<String, Object> listEffective(long tenantId, String userid, String cursor, Integer limit) {
        int n = (limit == null || limit <= 0) ? 20 : Math.min(limit, 50);
        OffsetDateTime now = OffsetDateTime.now();
        LambdaQueryWrapper<NfyaAnnouncement> qw = new LambdaQueryWrapper<NfyaAnnouncement>()
                .in(NfyaAnnouncement::getTenantId, List.of(0L, tenantId))
                .eq(NfyaAnnouncement::getStatus, "PUBLISHED")
                .le(NfyaAnnouncement::getEffectiveAt, now)
                .gt(NfyaAnnouncement::getExpireAt, now);
        applyCursor(qw, cursor);
        qw.orderByDesc(NfyaAnnouncement::getPublishedAt).orderByDesc(NfyaAnnouncement::getId);
        List<NfyaAnnouncement> rows = list(qw.last("LIMIT " + (n + 1)));
        boolean hasMore = rows.size() > n;
        List<NfyaAnnouncement> page = hasMore ? rows.subList(0, n) : rows;

        Map<Long, NfyaAnnouncementRead> myReads = readStatusMap(tenantId, userid, page);
        List<Map<String, Object>> items = new ArrayList<>();
        String nextCursor = null;
        for (NfyaAnnouncement a : page) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("announcement_id", String.valueOf(a.getId()));
            item.put("title", a.getTitle());
            item.put("scope", a.getScope());
            item.put("level", a.getLevel());
            item.put("content", a.getContent());
            item.put("link_url", a.getLinkUrl());
            item.put("need_confirm", a.getNeedConfirm());
            item.put("published_at", a.getPublishedAt() == null ? null : a.getPublishedAt().toInstant().toEpochMilli());
            item.put("my_status", myStatus(myReads.get(a.getId())));
            items.add(item);
            nextCursor = encodeCursor(a.getPublishedAt(), a.getId());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", items);
        data.put("next_cursor", hasMore ? nextCursor : null);
        data.put("has_more", hasMore);
        return data;
    }

    /** API-ANN-002 标记阅读（幂等；已过效期 10402，不存在/未生效/跨租户 10400；回执按公告归属域落库） */
    @Transactional
    public Map<String, Object> markRead(long tenantId, String userid, String announcementId) {
        NfyaAnnouncement a = requireVisible(tenantId, announcementId);
        NfyaAnnouncementRead row = findRow(a.getTenantId(), userid, a.getId());
        if (row == null) {
            NfyaAnnouncementRead r = new NfyaAnnouncementRead();
            r.setTenantId(a.getTenantId());
            r.setAnnouncementId(a.getId());
            r.setUserid(userid);
            r.setReadAt(OffsetDateTime.now());
            r.setExt("{}");
            insertQuietly(r);
        }
        return Map.of("read", true);
    }

    /**
     * API-ANN-003 确认（幂等；重复确认返回 confirmed:true）。
     * 首次确认判定以「条件 UPDATE 受影响行数」为准（评审第 5 步 P1：并发双确认下
     * check-then-act 会使 confirm_count 双计数）；insert 撞 uk 后回落条件 UPDATE 兜底。
     */
    @Transactional
    public Map<String, Object> confirm(long tenantId, String userid, String announcementId) {
        NfyaAnnouncement a = requireVisible(tenantId, announcementId);
        OffsetDateTime now = OffsetDateTime.now();
        // 回执域取公告归属租户（a.getTenantId()）：租户公告等价调用方域，平台公告归 0 域（admin/PAN 统计口径）
        long receiptTenantId = a.getTenantId();
        boolean firstConfirm;
        NfyaAnnouncementRead row = findRow(receiptTenantId, userid, a.getId());
        if (row == null) {
            NfyaAnnouncementRead r = new NfyaAnnouncementRead();
            r.setTenantId(receiptTenantId);
            r.setAnnouncementId(a.getId());
            r.setUserid(userid);
            r.setReadAt(now);
            r.setConfirmAt(now);
            r.setExt("{}");
            try {
                readMapper.insert(r);
                firstConfirm = true;
            } catch (DuplicateKeyException e) {
                // 并发已建回执：回落条件 UPDATE 判定是否由本次完成确认
                firstConfirm = confirmIfPending(receiptTenantId, userid, a.getId(), now);
            }
        } else {
            firstConfirm = confirmIfPending(receiptTenantId, userid, a.getId(), now);
        }
        if (firstConfirm) {
            update(new LambdaUpdateWrapper<NfyaAnnouncement>()
                    .eq(NfyaAnnouncement::getId, a.getId())
                    .setSql("confirm_count = confirm_count + 1"));
        }
        return Map.of("confirmed", true);
    }

    /** 仅当回执 confirm_at 为空时置位，返回是否由本次置位（原子判定，防并发双计数）；tenantId=回执归属域（公告归属租户） */
    private boolean confirmIfPending(long tenantId, String userid, Long announcementId, OffsetDateTime now) {
        int affected = readMapper.update(null, new LambdaUpdateWrapper<NfyaAnnouncementRead>()
                .eq(NfyaAnnouncementRead::getTenantId, tenantId)
                .eq(NfyaAnnouncementRead::getAnnouncementId, announcementId)
                .eq(NfyaAnnouncementRead::getUserid, userid)
                .isNull(NfyaAnnouncementRead::getConfirmAt)
                .set(NfyaAnnouncementRead::getConfirmAt, now));
        return affected == 1;
    }

    /**
     * 公告未确认数（need_confirm=1 的生效公告 - 该用户已确认回执），供 unread-count 合成（§5.3；LIMIT 防御性兜底）。
     * 回执域谓词 IN (0, :tid)：生效集含平台（0）与本租户两类公告，其回执域恒属此二域（见类注释口径）。
     */
    public long unconfirmedCount(long tenantId, String userid) {
        OffsetDateTime now = OffsetDateTime.now();
        List<NfyaAnnouncement> effective = list(new LambdaQueryWrapper<NfyaAnnouncement>()
                .select(NfyaAnnouncement::getId)
                .in(NfyaAnnouncement::getTenantId, List.of(0L, tenantId))
                .eq(NfyaAnnouncement::getStatus, "PUBLISHED")
                .eq(NfyaAnnouncement::getNeedConfirm, 1)
                .le(NfyaAnnouncement::getEffectiveAt, now)
                .gt(NfyaAnnouncement::getExpireAt, now)
                .last("LIMIT 100"));
        if (effective.isEmpty()) {
            return 0;
        }
        List<Long> ids = effective.stream().map(NfyaAnnouncement::getId).toList();
        Long confirmed = readMapper.selectCount(new LambdaQueryWrapper<NfyaAnnouncementRead>()
                .in(NfyaAnnouncementRead::getTenantId, List.of(0L, tenantId))
                .eq(NfyaAnnouncementRead::getUserid, userid)
                .isNotNull(NfyaAnnouncementRead::getConfirmAt)
                .in(NfyaAnnouncementRead::getAnnouncementId, ids));
        return effective.size() - (confirmed == null ? 0 : confirmed);
    }

    /** 生效窗口内可见性（platform/本租户 + PUBLISHED + 时间窗）；防探测：跨租户/未发布/草稿一律 10400 */
    private NfyaAnnouncement requireVisible(long tenantId, String announcementId) {
        long id;
        try {
            id = Long.parseLong(announcementId);
        } catch (NumberFormatException e) {
            throw new ApiException(10400, "公告不存在或未生效");
        }
        NfyaAnnouncement a = getById(id);
        OffsetDateTime now = OffsetDateTime.now();
        if (a == null || (a.getTenantId() != 0L && a.getTenantId() != tenantId)
                || !"PUBLISHED".equals(a.getStatus())
                || a.getEffectiveAt() == null || a.getEffectiveAt().isAfter(now)) {
            throw new ApiException(10400, "公告不存在或未生效");
        }
        if (a.getExpireAt() != null && !a.getExpireAt().isAfter(now)) {
            throw new ApiException(10402, "公告已失效");
        }
        return a;
    }

    private NfyaAnnouncementRead findRow(long tenantId, String userid, Long announcementId) {
        return readMapper.selectOne(new LambdaQueryWrapper<NfyaAnnouncementRead>()
                .eq(NfyaAnnouncementRead::getTenantId, tenantId)
                .eq(NfyaAnnouncementRead::getAnnouncementId, announcementId)
                .eq(NfyaAnnouncementRead::getUserid, userid)
                .last("LIMIT 1"));
    }

    private void insertQuietly(NfyaAnnouncementRead r) {
        try {
            readMapper.insert(r);
        } catch (DuplicateKeyException e) {
            // uk_nfya_ann_read_user 兜底：并发重复回执按幂等吞掉
        }
    }

    /** my_status 反查（回执域谓词 IN (0, :tid)：页内平台/本租户公告的回执域恒属此二域，见类注释口径） */
    private Map<Long, NfyaAnnouncementRead> readStatusMap(long tenantId, String userid, List<NfyaAnnouncement> page) {
        if (page.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = page.stream().map(NfyaAnnouncement::getId).toList();
        Map<Long, NfyaAnnouncementRead> map = new LinkedHashMap<>();
        for (NfyaAnnouncementRead r : readMapper.selectList(new LambdaQueryWrapper<NfyaAnnouncementRead>()
                .in(NfyaAnnouncementRead::getTenantId, List.of(0L, tenantId))
                .eq(NfyaAnnouncementRead::getUserid, userid)
                .in(NfyaAnnouncementRead::getAnnouncementId, ids))) {
            map.put(r.getAnnouncementId(), r);
        }
        return map;
    }

    private String myStatus(NfyaAnnouncementRead r) {
        if (r == null) {
            return "NONE";
        }
        return r.getConfirmAt() != null ? "CONFIRMED" : "READ";
    }

    private void applyCursor(LambdaQueryWrapper<NfyaAnnouncement> qw, String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return;
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("_");
            long millis = Long.parseLong(parts[0]);
            long id = Long.parseLong(parts[1]);
            OffsetDateTime c = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneOffset.UTC);
            qw.and(w -> w.lt(NfyaAnnouncement::getPublishedAt, c)
                    .or(w2 -> w2.eq(NfyaAnnouncement::getPublishedAt, c)
                            .lt(NfyaAnnouncement::getId, id)));
        } catch (Exception e) {
            throw new ApiException(10102, "cursor 格式不正确");
        }
    }

    private String encodeCursor(OffsetDateTime publishedAt, Long id) {
        if (publishedAt == null) {
            return null;
        }
        String raw = publishedAt.toInstant().toEpochMilli() + "_" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
