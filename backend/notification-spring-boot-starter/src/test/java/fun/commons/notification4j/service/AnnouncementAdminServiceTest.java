package fun.commons.notification4j.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.dto.PatchAnnouncementsAnnouncementIdRequest;
import fun.commons.notification4j.dto.PostAnnouncementsRequest;
import fun.commons.notification4j.entity.NfyaAnnouncement;
import fun.commons.notification4j.entity.NfyaAnnouncementRead;
import fun.commons.notification4j.mapper.NfyaAnnouncementMapper;
import fun.commons.notification4j.mapper.NfyaAnnouncementReadMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.OffsetDateTime;
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
 * 第 25 步 service 层 mock 单测：AnnouncementAdminService（TENANT 管理面）。
 * 创建 DRAFT（窗口非法 10102/need_confirm 10100/biz_no uk 10401/平台公告强制清空 channel_ids）/
 * 列表（status 枚举闸 10100 + total）/详情（防探测 10400）/PATCH 限 DRAFT（10402 + 条件更新防 TOFU）/
 * publish（10611 名额占位 = 未失效已发布 ≥20；条件更新败 10402；delivery_planned 接线）/
 * offline（非发布态 10402 + 条件更新）/stats（read/confirm 计数与 confirms 分页）。
 */
// VECTOR: TAG=step25-unit
class AnnouncementAdminServiceTest {

    private final NfyaAnnouncementMapper announcementMapper = mock(NfyaAnnouncementMapper.class);
    private final NfyaAnnouncementReadMapper readMapper = mock(NfyaAnnouncementReadMapper.class);
    private final DeliveryPlanService planService = mock(DeliveryPlanService.class);

    private AnnouncementAdminService newService() {
        return new AnnouncementAdminService(announcementMapper, readMapper, new ObjectMapper(), planService);
    }

    private static final long EFF = 1_780_000_000_000L; // 固定锚点毫秒（不依赖时钟）
    private static final long EXP = 1_790_000_000_000L;

    private static PostAnnouncementsRequest postReq(String bizNo) {
        return new PostAnnouncementsRequest("标题", "内容", null, EFF, EXP, 0, null, List.of("1"), bizNo);
    }

    private static NfyaAnnouncement draft(long id) {
        NfyaAnnouncement a = new NfyaAnnouncement();
        a.setId(id);
        a.setTenantId(1L);
        a.setStatus("DRAFT");
        a.setTitle("旧标题");
        a.setContent("旧内容");
        a.setLevel("IMPORTANT");
        a.setNeedConfirm(0);
        a.setLinkUrl("");
        a.setChannelIds("[\"1\"]");
        a.setEffectiveAt(OffsetDateTime.parse("2026-06-01T00:00Z"));
        a.setExpireAt(OffsetDateTime.parse("2026-12-01T00:00Z"));
        return a;
    }

    // ---- create / createPlatform ----

    @Test
    void create_builds_draft_with_normalized_defaults() {
        when(announcementMapper.insert(any(NfyaAnnouncement.class))).thenAnswer(inv -> {
            inv.<NfyaAnnouncement>getArgument(0).setId(66L);
            return 1;
        });
        var data = newService().create(1L, postReq("BIZ-1"));
        ArgumentCaptor<NfyaAnnouncement> cap = ArgumentCaptor.forClass(NfyaAnnouncement.class);
        verify(announcementMapper).insert(cap.capture());
        NfyaAnnouncement a = cap.getValue();
        assertThat(a.getTenantId()).isEqualTo(1L);
        assertThat(a.getScope()).isEqualTo("TENANT");
        assertThat(a.getStatus()).isEqualTo("DRAFT");
        assertThat(a.getLevel()).isEqualTo("IMPORTANT"); // level 缺省
        assertThat(a.getNeedConfirm()).isZero();
        assertThat(a.getConfirmCount()).isZero();
        assertThat(a.getLinkUrl()).isEmpty();
        assertThat(a.getChannelIds()).isEqualTo("[\"1\"]");
        assertThat(a.getExt()).isEqualTo("{}");
        assertThat(data).containsEntry("announcement_id", "66").containsEntry("status", "DRAFT");
    }

    @Test
    void create_duplicate_biz_no_throws_10401() {
        when(announcementMapper.insert(any(NfyaAnnouncement.class)))
                .thenThrow(new DuplicateKeyException("uk"));
        assertThatThrownBy(() -> newService().create(1L, postReq("DUP")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10401));
    }

    @Test
    void create_invalid_window_or_need_confirm_rejected() {
        assertThatThrownBy(() -> newService().create(1L, new PostAnnouncementsRequest(
                "t", "c", null, null, EXP, null, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10101);
                    assertThat(e.getMessage()).contains("effective_at");
                });
        assertThatThrownBy(() -> newService().create(1L, new PostAnnouncementsRequest(
                "t", "c", null, EXP, EFF, null, null, null, null))) // expire ≤ effective
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10102));
        assertThatThrownBy(() -> newService().create(1L, new PostAnnouncementsRequest(
                "t", "c", null, EFF, EXP, 2, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10100));
        verify(announcementMapper, never()).insert(any(NfyaAnnouncement.class));
    }

    @Test
    void create_platform_forces_empty_channels_and_platform_scope() {
        when(announcementMapper.insert(any(NfyaAnnouncement.class))).thenAnswer(inv -> {
            inv.<NfyaAnnouncement>getArgument(0).setId(1L);
            return 1;
        });
        newService().createPlatform(new PostAnnouncementsRequest("t", "c", null, EFF, EXP, null,
                "https://x", List.of("9"), "  ")); // bizNo blank → 归 ""
        ArgumentCaptor<NfyaAnnouncement> cap = ArgumentCaptor.forClass(NfyaAnnouncement.class);
        verify(announcementMapper).insert(cap.capture());
        assertThat(cap.getValue().getTenantId()).isZero(); // 平台域
        assertThat(cap.getValue().getScope()).isEqualTo("PLATFORM");
        assertThat(cap.getValue().getChannelIds()).isEqualTo("[]"); // 强制清空
        assertThat(cap.getValue().getBizNo()).isEmpty(); // 空白 biz_no 归 ""
    }

    // ---- list / detail ----

    @Test
    void list_rejects_illegal_status_enum() {
        assertThatThrownBy(() -> newService().list(1L, "CLOSED", null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10100));
    }

    @Test
    void list_maps_rows_and_total() {
        when(announcementMapper.selectList(any())).thenReturn(List.of(draft(1L)));
        when(announcementMapper.selectCount(any())).thenReturn(7L);
        var data = newService().list(1L, "DRAFT", 0, 10);
        assertThat(data.get("total")).isEqualTo(7L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("list");
        assertThat(items.get(0)).containsEntry("announcement_id", "1")
                .containsEntry("channel_ids", List.of("1")).containsEntry("status", "DRAFT");
        assertThat(items.get(0)).containsKey("confirm_count"); // 回执实数口径
    }

    @Test
    void detail_missing_or_cross_tenant_throws_10400() {
        when(announcementMapper.selectById(1L)).thenReturn(null);
        assertThatThrownBy(() -> newService().detail(1L, "1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
        NfyaAnnouncement foreign = draft(1L);
        foreign.setTenantId(9L);
        when(announcementMapper.selectById(1L)).thenReturn(foreign);
        assertThatThrownBy(() -> newService().detail(1L, "1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
    }

    @Test
    void detail_maps_full_fields_with_confirm_count() {
        when(announcementMapper.selectById(1L)).thenReturn(draft(1L));
        when(readMapper.selectCount(any())).thenReturn(3L);
        var data = newService().detail(1L, "1");
        assertThat(data).containsEntry("announcement_id", "1").containsEntry("title", "旧标题")
                .containsEntry("confirm_count", 3L)
                .containsEntry("effective_at",
                        draft(1L).getEffectiveAt().toInstant().toEpochMilli())
                .containsEntry("expire_at", draft(1L).getExpireAt().toInstant().toEpochMilli())
                .containsEntry("published_at", null);
    }

    // ---- patch（限 DRAFT + 条件更新防 TOFU）----

    @Test
    void patch_applies_fields_and_conditional_update() {
        when(announcementMapper.selectById(1L)).thenReturn(draft(1L));
        when(announcementMapper.update(any(NfyaAnnouncement.class), any())).thenReturn(1);
        var data = newService().patch(1L, "1", new PatchAnnouncementsAnnouncementIdRequest(
                "新标题", "新内容", "NORMAL", EFF, EXP, 1, null, List.of("2", "3")));
        ArgumentCaptor<NfyaAnnouncement> cap = ArgumentCaptor.forClass(NfyaAnnouncement.class);
        verify(announcementMapper).update(cap.capture(), any());
        assertThat(cap.getValue().getTitle()).isEqualTo("新标题");
        assertThat(cap.getValue().getNeedConfirm()).isEqualTo(1);
        assertThat(cap.getValue().getChannelIds()).isEqualTo("[\"2\",\"3\"]");
        assertThat(data).containsEntry("announcement_id", "1").containsEntry("status", "DRAFT");
    }

    @Test
    void patch_non_draft_or_lost_race_throws_10402() {
        NfyaAnnouncement published = draft(1L);
        published.setStatus("PUBLISHED");
        when(announcementMapper.selectById(1L)).thenReturn(published);
        assertThatThrownBy(() -> newService().patch(1L, "1",
                new PatchAnnouncementsAnnouncementIdRequest("t", null, null, null, null, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10402));

        when(announcementMapper.selectById(1L)).thenReturn(draft(1L)); // 加载是草稿，条件更新败（并发发布）
        when(announcementMapper.update(any(NfyaAnnouncement.class), any())).thenReturn(0);
        assertThatThrownBy(() -> newService().patch(1L, "1",
                new PatchAnnouncementsAnnouncementIdRequest("t", null, null, null, null, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10402));
    }

    @Test
    void patch_invalid_need_confirm_or_window_throws() {
        when(announcementMapper.selectById(1L)).thenReturn(draft(1L));
        assertThatThrownBy(() -> newService().patch(1L, "1", new PatchAnnouncementsAnnouncementIdRequest(
                null, null, null, null, null, 7, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10100));
        assertThatThrownBy(() -> newService().patch(1L, "1", new PatchAnnouncementsAnnouncementIdRequest(
                null, null, null, EXP, EFF, null, null, null))) // 改成倒挂窗口
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10102));
        verify(announcementMapper, never()).update(any(NfyaAnnouncement.class), any());
    }

    // ---- publish（10611 名额 + 条件更新 + 外发接线）----

    @Test
    void publish_success_plans_delivery() {
        when(announcementMapper.selectById(1L)).thenReturn(draft(1L));
        when(announcementMapper.selectCount(any())).thenReturn(19L); // 未失效已发布 < 20
        when(announcementMapper.update(any(NfyaAnnouncement.class), any())).thenReturn(1);
        when(planService.planForAnnouncement(any(NfyaAnnouncement.class))).thenReturn(5);

        var data = newService().publish(1L, "1");
        ArgumentCaptor<NfyaAnnouncement> cap = ArgumentCaptor.forClass(NfyaAnnouncement.class);
        verify(announcementMapper).update(cap.capture(), any());
        assertThat(cap.getValue().getStatus()).isEqualTo("PUBLISHED");
        assertThat(cap.getValue().getPublishedAt()).isNotNull();
        verify(planService).planForAnnouncement(cap.getValue()); // 同事务外发展开
        assertThat(data).containsEntry("status", "PUBLISHED").containsEntry("delivery_planned", 5);
    }

    @Test
    void publish_quota_or_non_draft_or_lost_race_rejected() {
        assertThatThrownBy(() -> newService().publish(1L, "1")) // 不存在
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));

        NfyaAnnouncement published = draft(1L);
        published.setStatus("PUBLISHED");
        when(announcementMapper.selectById(1L)).thenReturn(published);
        assertThatThrownBy(() -> newService().publish(1L, "1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10402));

        when(announcementMapper.selectById(1L)).thenReturn(draft(1L));
        when(announcementMapper.selectCount(any())).thenReturn(20L); // 名额占满（含未来窗口占位）
        assertThatThrownBy(() -> newService().publish(1L, "1"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10611);
                    assertThat(e.getMessage()).contains("20");
                });

        when(announcementMapper.selectCount(any())).thenReturn(0L);
        when(announcementMapper.update(any(NfyaAnnouncement.class), any())).thenReturn(0); // 并发改态
        assertThatThrownBy(() -> newService().publish(1L, "1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10402));
        verify(planService, never()).planForAnnouncement(any(NfyaAnnouncement.class));
    }

    // ---- offline ----

    @Test
    void offline_published_announcement_succeeds() {
        NfyaAnnouncement published = draft(1L);
        published.setStatus("PUBLISHED");
        when(announcementMapper.selectById(1L)).thenReturn(published);
        when(announcementMapper.update(any(NfyaAnnouncement.class), any())).thenReturn(1);
        assertThat(newService().offline(1L, "1"))
                .isEqualTo(Map.of("announcement_id", "1", "status", "OFFLINE"));
    }

    @Test
    void offline_non_published_or_lost_race_throws_10402() {
        when(announcementMapper.selectById(1L)).thenReturn(draft(1L)); // 草稿态
        assertThatThrownBy(() -> newService().offline(1L, "1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10402));
        NfyaAnnouncement published = draft(1L);
        published.setStatus("PUBLISHED");
        when(announcementMapper.selectById(1L)).thenReturn(published);
        when(announcementMapper.update(any(NfyaAnnouncement.class), any())).thenReturn(0); // 并发下线
        assertThatThrownBy(() -> newService().offline(1L, "1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10402));
    }

    // ---- stats ----

    @Test
    void stats_maps_read_and_confirm_counts() {
        NfyaAnnouncement published = draft(1L);
        published.setStatus("PUBLISHED");
        when(announcementMapper.selectById(1L)).thenReturn(published);
        when(readMapper.selectCount(any())).thenReturn(9L, 2L); // read_count, confirm_count
        NfyaAnnouncementRead r = new NfyaAnnouncementRead();
        r.setUserid("u1");
        r.setConfirmAt(OffsetDateTime.parse("2026-06-02T00:00Z"));
        when(readMapper.selectList(any())).thenReturn(List.of(r));

        var data = newService().stats(1L, "1", 0, 10);
        assertThat(data).containsEntry("read_count", 9L).containsEntry("confirm_count", 2L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> confirms = (List<Map<String, Object>>) data.get("confirms");
        assertThat(confirms).hasSize(1);
        assertThat(confirms.get(0)).containsEntry("userid", "u1").containsEntry("confirm_at",
                OffsetDateTime.parse("2026-06-02T00:00Z").toInstant().toEpochMilli());
    }
}
