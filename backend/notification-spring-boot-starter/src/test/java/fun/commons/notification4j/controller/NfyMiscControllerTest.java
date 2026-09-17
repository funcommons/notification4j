package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.PlatformDomain;
import fun.commons.notification4j.service.BatchJobService;
import fun.commons.notification4j.service.StatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 26 步 controller 层切片单测（薄委托补扫）：双统计概览 + 异步 Job 轮询。
 * 平台域（/nfy/platform）不取上下文 / 租户域（/nfy/api）取 TokenContext.tenant_id /
 * 路径变量 job_id 透传 / @RequiresToken+域注解存在性。
 */
// VECTOR: TAG=step26-unit
class NfyMiscControllerTest {

    private final StatsService statsService = mock(StatsService.class);
    private final BatchJobService batchJobService = mock(BatchJobService.class);

    private MockMvc tenantMvc;   // 租户域：需要 TokenContext 注入
    private MockMvc platformMvc; // 平台域：无上下文依赖

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StandaloneMockMvcBuilder tenantBuilder =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                        new NfyAdminStatsController(statsService), new NfyJobController(batchJobService));
        tenantMvc = ControllerTestSupport.applyRuntimeContext(tenantBuilder).build();

        platformMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new NfyPlatformStatsController(statsService)).build();
    }

    @Test
    void admin_stats_overview_passes_tenant_from_token_context() throws Exception {
        when(statsService.tenantOverview(1L)).thenReturn(Map.of("today_sent", 5));

        tenantMvc.perform(get("/nfy/api/v1/admin/stats/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.today_sent").value(5));
        verify(statsService).tenantOverview(1L);
    }

    @Test
    void platform_stats_overview_is_tenant_context_free() throws Exception {
        when(statsService.platformOverview()).thenReturn(Map.of("tenant_count", 3));

        platformMvc.perform(get("/nfy/platform/api/v1/stats/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenant_count").value(3));
        verify(statsService).platformOverview();
    }

    @Test
    void job_poll_forwards_job_id_path_variable() throws Exception {
        when(batchJobService.get(1L, "job-9")).thenReturn(Map.of("state", "RUNNING", "finished", 40));

        tenantMvc.perform(get("/nfy/api/v1/runtime/jobs/job-9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("RUNNING"));
        verify(batchJobService).get(1L, "job-9");
    }

    @Test
    void misc_route_guards_are_declared_via_annotations() {
        org.assertj.core.api.Assertions.assertThat(
                        NfyPlatformStatsController.class.getAnnotation(PlatformDomain.class)).isNotNull();
        for (Class<?> c : new Class<?>[]{NfyPlatformStatsController.class, NfyAdminStatsController.class,
                NfyJobController.class}) {
            RequiresToken t = c.getAnnotation(RequiresToken.class);
            org.assertj.core.api.Assertions.assertThat(t).isNotNull();
            org.assertj.core.api.Assertions.assertThat(t.value()).isEqualTo("TENANT");
        }
    }
}
