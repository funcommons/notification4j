package fun.commons.notification4j.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.entity.NfyaAnnouncement;
import fun.commons.notification4j.entity.NfyaAnnouncementRead;
import fun.commons.notification4j.mapper.NfyaAnnouncementMapper;
import fun.commons.notification4j.mapper.NfyaAnnouncementReadMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 25 步 service 层 mock 单测：AnnouncementService（runtime 读侧）。
 * 生效列表（fanout-on-read + my_status 三态）/标记阅读幂等（uk 吞并发）/确认原子计数
 * （首判=条件 UPDATE 受影响行数；insert 撞 uk 回落条件 UPDATE；已确认不再 +1）/
 * unconfirmedCount 扣减/可见性窗口（未发布/未生效/跨租户 10400，过效期 10402）/
 * 第 28 步回执域口径：平台公告回执落公告归属域（0），读状态/未确认查询双域 IN (0, :tid)。
 * baseMapper（NfyaAnnouncementMapper）反射注入；confirmIfPending 走 LambdaUpdateWrapper#set → 预初始化 TableInfo。
 */
// VECTOR: TAG=step25-unit
class AnnouncementServiceReadTest {

    private final NfyaAnnouncementMapper announcementMapper = mock(NfyaAnnouncementMapper.class);
    private final NfyaAnnouncementReadMapper readMapper = mock(NfyaAnnouncementReadMapper.class);

    @BeforeAll
    static void initTableInfos() {
        ServiceMockSupport.initTableInfo(fun.commons.notification4j.entity.NfyaAnnouncement.class,
                fun.commons.notification4j.entity.NfyaAnnouncementRead.class);
    }

    private AnnouncementService newService() {
        return ServiceMockSupport.injectBaseMapper(new AnnouncementService(readMapper), announcementMapper);
    }

    private static final OffsetDateTime T_PUB = OffsetDateTime.parse("2026-06-01T00:00Z");

    /** 生效窗口恒含「现在」的已发布公告（时间戳取固定锚点 ± 大跨度，不依赖时钟推进方向） */
    private static NfyaAnnouncement published(long id, long tenantId) {
        NfyaAnnouncement a = new NfyaAnnouncement();
        a.setId(id);
        a.setTenantId(tenantId);
        a.setStatus("PUBLISHED");
        a.setTitle("公告" + id);
        a.setScope("TENANT");
        a.setLevel("IMPORTANT");
        a.setContent("内容");
        a.setPublishedAt(T_PUB);
        a.setEffectiveAt(T_PUB.minusDays(3650));
        a.setExpireAt(T_PUB.plusDays(3650));
        return a;
    }

    private static NfyaAnnouncementRead receipt(long announcementId, OffsetDateTime confirmAt) {
        NfyaAnnouncementRead r = new NfyaAnnouncementRead();
        r.setAnnouncementId(announcementId);
        r.setUserid("u1");
        r.setConfirmAt(confirmAt);
        return r;
    }

    // ---- listEffective ----

    @Test
    void list_effective_maps_items_with_my_status_three_states() {
        when(announcementMapper.selectList(any())).thenReturn(List.of(
                published(1L, 1L), published(2L, 1L), published(3L, 0L))); // 平台公告合并
        when(readMapper.selectList(any())).thenReturn(List.of(
                receipt(2L, null), receipt(3L, T_PUB))); // 2=已读未确认 3=已确认

        Map<String, Object> data = newService().listEffective(1L, "u1", null, null);
        assertThat(data.get("has_more")).isEqualTo(false);
        assertThat(data.get("next_cursor")).isNull();
        List<Map<String, Object>> items = items(data);
        assertThat(items).hasSize(3);
        assertThat(items.get(0).get("my_status")).isEqualTo("NONE");
        assertThat(items.get(1).get("my_status")).isEqualTo("READ");
        assertThat(items.get(2).get("my_status")).isEqualTo("CONFIRMED");
        assertThat(items.get(0)).containsEntry("announcement_id", "1")
                .containsEntry("published_at", T_PUB.toInstant().toEpochMilli())
                .containsEntry("need_confirm", null);
    }

    @Test
    void list_effective_has_more_encodes_cursor() {
        when(announcementMapper.selectList(any())).thenReturn(List.of(
                published(1L, 1L), published(2L, 1L), published(3L, 1L)));
        when(readMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> data = newService().listEffective(1L, "u1", null, 2);
        assertThat(data.get("has_more")).isEqualTo(true);
        List<Map<String, Object>> items = items(data);
        assertThat(items).hasSize(2); // 截断
        String raw = new String(Base64.getUrlDecoder().decode((String) data.get("next_cursor")),
                StandardCharsets.UTF_8);
        assertThat(raw).isEqualTo(T_PUB.toInstant().toEpochMilli() + "_2");
    }

    @Test
    void list_effective_invalid_cursor_throws_10102() {
        assertThatThrownBy(() -> newService().listEffective(1L, "u1", "@@", 10))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10102));
    }

    @Test
    void list_effective_empty_page_skips_receipt_lookup() {
        when(announcementMapper.selectList(any())).thenReturn(List.of());
        Map<String, Object> data = newService().listEffective(1L, "u1", null, null);
        assertThat(items(data)).isEmpty();
        verify(readMapper, never()).selectList(any());
    }

    // ---- markRead ----

    @Test
    void mark_read_inserts_receipt_when_absent() {
        when(announcementMapper.selectById(1L)).thenReturn(published(1L, 1L));
        when(readMapper.selectOne(any())).thenReturn(null);
        assertThat(newService().markRead(1L, "u1", "1")).isEqualTo(Map.of("read", true));
        ArgumentCaptor<NfyaAnnouncementRead> cap = ArgumentCaptor.forClass(NfyaAnnouncementRead.class);
        verify(readMapper).insert(cap.capture());
        assertThat(cap.getValue().getAnnouncementId()).isEqualTo(1L);
        assertThat(cap.getValue().getReadAt()).isNotNull();
    }

    @Test
    void mark_read_duplicate_receipt_is_swallowed() {
        when(announcementMapper.selectById(1L)).thenReturn(published(1L, 1L));
        when(readMapper.selectOne(any())).thenReturn(null); // 查无回执但 insert 撞 uk（并发）
        when(readMapper.insert(any(NfyaAnnouncementRead.class)))
                .thenThrow(new DuplicateKeyException("uk"));
        assertThat(newService().markRead(1L, "u1", "1")).isEqualTo(Map.of("read", true));
    }

    @Test
    void mark_read_visibility_window_rejections() {
        AnnouncementService service = newService();
        NfyaAnnouncement expired = published(1L, 1L);
        expired.setExpireAt(T_PUB.minusDays(3650)); // 已过效期
        when(announcementMapper.selectById(1L)).thenReturn(expired);
        assertThatThrownBy(() -> service.markRead(1L, "u1", "1"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10402);
                    assertThat(e.getMessage()).contains("已失效");
                });

        NfyaAnnouncement future = published(2L, 1L);
        future.setEffectiveAt(T_PUB.plusDays(3650)); // 未生效
        when(announcementMapper.selectById(2L)).thenReturn(future);
        assertThatThrownBy(() -> service.markRead(1L, "u1", "2"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));

        when(announcementMapper.selectById(3L)).thenReturn(published(3L, 9L)); // 跨租户
        assertThatThrownBy(() -> service.markRead(1L, "u1", "3"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));

        NfyaAnnouncement draft = published(4L, 1L);
        draft.setStatus("DRAFT");
        when(announcementMapper.selectById(4L)).thenReturn(draft);
        assertThatThrownBy(() -> service.markRead(1L, "u1", "4"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));

        assertThatThrownBy(() -> service.markRead(1L, "u1", "abc")) // 非数字 id
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
        assertThatThrownBy(() -> service.markRead(1L, "u1", "999")) // 不存在
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
    }

    // ---- confirm（原子计数）----

    @Test
    void confirm_first_time_inserts_receipt_and_increments_counter() {
        when(announcementMapper.selectById(1L)).thenReturn(published(1L, 1L));
        when(readMapper.selectOne(any())).thenReturn(null);
        when(announcementMapper.update(any(), any())).thenReturn(1);

        assertThat(newService().confirm(1L, "u1", "1")).isEqualTo(Map.of("confirmed", true));
        verify(readMapper).insert(any(NfyaAnnouncementRead.class));
        verify(announcementMapper).update(any(), any()); // confirm_count + 1 仅首认执行
    }

    @Test
    void confirm_insert_clash_falls_back_to_conditional_update() {
        when(announcementMapper.selectById(1L)).thenReturn(published(1L, 1L));
        when(readMapper.selectOne(any())).thenReturn(null);
        when(readMapper.insert(any(NfyaAnnouncementRead.class))).thenThrow(new DuplicateKeyException("uk"));
        when(readMapper.update(any(), any())).thenReturn(1); // 条件 UPDATE 命中：由本次完成确认
        when(announcementMapper.update(any(), any())).thenReturn(1);

        assertThat(newService().confirm(1L, "u1", "1")).isEqualTo(Map.of("confirmed", true));
        verify(announcementMapper).update(any(), any());
    }

    @Test
    void confirm_existing_pending_receipt_sets_and_increments() {
        when(announcementMapper.selectById(1L)).thenReturn(published(1L, 1L));
        when(readMapper.selectOne(any())).thenReturn(receipt(1L, null)); // 已读未确认
        when(readMapper.update(any(), any())).thenReturn(1);
        when(announcementMapper.update(any(), any())).thenReturn(1);

        assertThat(newService().confirm(1L, "u1", "1")).isEqualTo(Map.of("confirmed", true));
        verify(announcementMapper).update(any(), any());
    }

    @Test
    void confirm_already_confirmed_does_not_increment() {
        when(announcementMapper.selectById(1L)).thenReturn(published(1L, 1L));
        when(readMapper.selectOne(any())).thenReturn(receipt(1L, T_PUB)); // confirm_at 已置位
        when(readMapper.update(any(), any())).thenReturn(0); // 条件更新未命中

        assertThat(newService().confirm(1L, "u1", "1")).isEqualTo(Map.of("confirmed", true)); // 幂等
        verify(announcementMapper, never()).update(any(), any()); // 不双计数
    }

    // ---- 回执域口径（第 28 步：回执行归属域 = 公告归属租户）----

    @Test
    void confirm_platform_announcement_writes_receipt_into_owner_domain() {
        when(announcementMapper.selectById(1L)).thenReturn(published(1L, 0L)); // 平台公告归属 0 域，调用方租户 1
        when(readMapper.selectOne(any())).thenReturn(null);
        when(announcementMapper.update(any(), any())).thenReturn(1);

        assertThat(newService().confirm(1L, "u1", "1")).isEqualTo(Map.of("confirmed", true));
        ArgumentCaptor<NfyaAnnouncementRead> cap = ArgumentCaptor.forClass(NfyaAnnouncementRead.class);
        verify(readMapper).insert(cap.capture());
        assertThat(cap.getValue().getTenantId()).isZero(); // 回执归 0 域（修前按调用方租户落库 → PAN 口径恒 0）
        verify(announcementMapper).update(any(), any()); // confirm_count + 1
    }

    @Test
    void mark_read_platform_announcement_receipt_into_owner_domain() {
        when(announcementMapper.selectById(1L)).thenReturn(published(1L, 0L));
        when(readMapper.selectOne(any())).thenReturn(null);

        assertThat(newService().markRead(1L, "u1", "1")).isEqualTo(Map.of("read", true));
        ArgumentCaptor<NfyaAnnouncementRead> cap = ArgumentCaptor.forClass(NfyaAnnouncementRead.class);
        verify(readMapper).insert(cap.capture());
        assertThat(cap.getValue().getTenantId()).isZero(); // 与 confirm 同口径
    }

    @Test
    void read_status_and_unconfirmed_queries_span_both_receipt_domains() {
        when(announcementMapper.selectList(any())).thenReturn(List.of(published(1L, 1L), published(2L, 0L)));
        when(readMapper.selectList(any())).thenReturn(List.of());
        newService().listEffective(1L, "u1", null, null);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<NfyaAnnouncementRead>> listCap =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(readMapper).selectList(listCap.capture());
        // MP 参数惰性注册：getSqlSegment()（SQL 生成期，TableInfo 已初始化）后 paramNameValuePairs 才可见
        listCap.getValue().getSqlSegment();
        assertThat(listCap.getValue().getParamNameValuePairs().values())
                .contains(0L, 1L); // 回执域谓词 IN (0, :tid)（平台 0 + 本租户）

        when(readMapper.selectCount(any())).thenReturn(0L);
        newService().unconfirmedCount(1L, "u1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<NfyaAnnouncementRead>> countCap =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(readMapper).selectCount(countCap.capture());
        countCap.getValue().getSqlSegment();
        assertThat(countCap.getValue().getParamNameValuePairs().values())
                .contains(0L, 1L); // unconfirmedCount 同口径
    }

    // ---- unconfirmedCount ----

    @Test
    void unconfirmed_count_subtracts_confirmed_receipts() {
        when(announcementMapper.selectList(any())).thenReturn(List.of(
                published(1L, 1L), published(2L, 1L)));
        when(readMapper.selectCount(any())).thenReturn(1L);
        assertThat(newService().unconfirmedCount(1L, "u1")).isEqualTo(1L);
    }

    @Test
    void unconfirmed_count_zero_when_no_effective_announcements() {
        when(announcementMapper.selectList(any())).thenReturn(List.of());
        assertThat(newService().unconfirmedCount(1L, "u1")).isZero();
        verify(readMapper, never()).selectCount(any());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("list");
    }
}
