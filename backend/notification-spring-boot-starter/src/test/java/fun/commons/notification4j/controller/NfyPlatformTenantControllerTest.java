package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.PlatformDomain;
import fun.commons.notification4j.dto.PatchTenantRequest;
import fun.commons.notification4j.dto.PostTenantsRequest;
import fun.commons.notification4j.service.PlatformTenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 26 步 controller 层切片单测：NfyPlatformTenantController（PTE-001~004 平台域租户生命周期）。
 * 平台域路由 /nfy/platform/api/v1/tenants（@PlatformDomain）/路径参数 tenant_open_id（内部
 * 雪花 id 不出网）/action 状态机参数绑定/@Valid（email 格式）/信封/注解守卫存在性。
 * PlatformTenantService 全 mock（平台域方法不取租户上下文 → 无需 TokenContext 注入）。
 */
// VECTOR: TAG=step26-unit
class NfyPlatformTenantControllerTest {

    private final PlatformTenantService platformTenantService = mock(PlatformTenantService.class);

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        StandaloneMockMvcBuilder builder =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                new NfyPlatformTenantController(platformTenantService))
                        .setControllerAdvice(new NfyExceptionHandler())
                        .setValidator(ControllerTestSupport.validator());
        mvc = builder.build(); // 平台域不依赖 TokenContext/X-User-Id
    }

    @Test
    void list_and_detail_use_platform_paths_with_open_id_path_variable() throws Exception {
        when(platformTenantService.list()).thenReturn(Map.of("list", java.util.List.of()));
        mvc.perform(get("/nfy/platform/api/v1/tenants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        when(platformTenantService.detail("op-123")).thenReturn(Map.of("open_id", "op-123", "email", "a***@x.com"));
        mvc.perform(get("/nfy/platform/api/v1/tenants/op-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.open_id").value("op-123")); // 内部雪花 id 不出网口径
        verify(platformTenantService).detail("op-123");
    }

    @Test
    void create_binds_body_and_returns_envelope() throws Exception {
        when(platformTenantService.create(any(PostTenantsRequest.class)))
                .thenReturn(Map.of("open_id", "op-new", "tenant_secret", "one-time-secret"));

        mvc.perform(post("/nfy/platform/api/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"租户甲","email":"ops@tenant.com","description":"说明"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.open_id").value("op-new"))
                .andExpect(jsonPath("$.data.tenant_secret").value("one-time-secret")); // 明文仅此一次（契约面）
    }

    @Test
    void create_invalid_email_is_400_without_service_call() throws Exception {
        mvc.perform(post("/nfy/platform/api/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"租户乙\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest()); // @Email
        org.mockito.Mockito.verifyNoInteractions(platformTenantService);
    }

    @Test
    void patch_and_reset_secret_and_status_forward_open_id() throws Exception {
        when(platformTenantService.patch(eq("op-123"), any(PatchTenantRequest.class)))
                .thenReturn(Map.of("name", "新名"));
        mvc.perform(patch("/nfy/platform/api/v1/tenants/op-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新名\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("新名"));

        when(platformTenantService.resetSecret("op-123"))
                .thenReturn(Map.of("tenant_secret", "rotated"));
        mvc.perform(post("/nfy/platform/api/v1/tenants/op-123/reset-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenant_secret").value("rotated"));

        when(platformTenantService.changeStatus("op-123", "SUSPEND"))
                .thenReturn(Map.of("status", "SUSPENDED"));
        mvc.perform(post("/nfy/platform/api/v1/tenants/op-123/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"SUSPEND\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED")); // PTE-004 状态机入口
        verify(platformTenantService).changeStatus("op-123", "SUSPEND");
    }

    @Test
    void route_guards_are_declared_via_annotations() {
        RequiresToken token = NfyPlatformTenantController.class.getAnnotation(RequiresToken.class);
        assertThat(token).isNotNull();
        assertThat(token.value()).isEqualTo("TENANT");
        assertThat(NfyPlatformTenantController.class.getAnnotation(PlatformDomain.class)).isNotNull();
    }
}
