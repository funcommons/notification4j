package fun.commons.notification4j.controller;

import fun.commons.notification4j.dto.PatchAnnouncementsAnnouncementIdRequest;
import fun.commons.notification4j.dto.PostAnnouncementsRequest;
import fun.commons.notification4j.service.AnnouncementAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 28 步 controller 覆盖缺口补齐：NfyPlatformAnnouncementController（API-PAN 平台公告）。
 * 缺口行为 patch 体（合并报告 L67）：PATCH /{announcement_id} 的 channel_ids 恒清空净化
 * （平台域无渠道资源，忽略提交值）+ tenant 0 硬编码透传；create→createPlatform 委托补验。
 * 平台域不依赖 TokenContext/X-User-Id（同 NfyPlatformTenantControllerTest 口径）。
 */
// VECTOR: TAG=step28-unit
class NfyPlatformAnnouncementControllerTest {

    private final AnnouncementAdminService announcementAdminService = mock(AnnouncementAdminService.class);

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new NfyPlatformAnnouncementController(announcementAdminService))
                .setControllerAdvice(new NfyExceptionHandler())
                .setValidator(ControllerTestSupport.validator())
                .setMessageConverters(ControllerTestSupport.snakeCaseJsonConverter()) // effective_at ↔ effectiveAt
                .build();
    }

    @Test
    void patch_forces_channel_ids_empty_and_forwards_tenant_zero() throws Exception {
        when(announcementAdminService.patch(eq(0L), eq("7"),
                any(PatchAnnouncementsAnnouncementIdRequest.class))).thenReturn(Map.of("status", "DRAFT"));

        mvc.perform(patch("/nfy/platform/api/v1/announcements/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"新标题\",\"channel_ids\":[\"1\",\"2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        ArgumentCaptor<PatchAnnouncementsAnnouncementIdRequest> cap =
                ArgumentCaptor.forClass(PatchAnnouncementsAnnouncementIdRequest.class);
        verify(announcementAdminService).patch(eq(0L), eq("7"), cap.capture());
        assertThat(cap.getValue().title()).isEqualTo("新标题");
        assertThat(cap.getValue().channelIds()).isEmpty(); // 净化：提交值忽略，恒空列表
    }

    @Test
    void create_delegates_to_create_platform_with_snake_case_binding() throws Exception {
        when(announcementAdminService.createPlatform(any(PostAnnouncementsRequest.class)))
                .thenReturn(Map.of("announcement_id", "1", "status", "DRAFT"));

        mvc.perform(post("/nfy/platform/api/v1/announcements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"平台公告","content":"c","effective_at":1780000000000,"expire_at":1790000000000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.announcement_id").value("1"));
        verify(announcementAdminService).createPlatform(any(PostAnnouncementsRequest.class));
    }
}
