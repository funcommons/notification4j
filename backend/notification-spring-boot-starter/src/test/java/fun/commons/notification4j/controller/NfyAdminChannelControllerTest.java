package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.TenantDomain;
import fun.commons.notification4j.dto.PatchChannelsChannelIdRequest;
import fun.commons.notification4j.dto.PostChannelsRequest;
import fun.commons.notification4j.service.ChannelAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 26 步 controller 层切片单测：NfyAdminChannelController（ACH-001~004 admin 渠道面）。
 * REST 动词×路由（GET/POST/POST verify/PATCH/DELETE）/路径参数 channelId 透传/
 * @Valid 400/信封/注解守卫存在性。ChannelAdminService 全 mock。
 */
// VECTOR: TAG=step26-unit
class NfyAdminChannelControllerTest {

    private final ChannelAdminService channelAdminService = mock(ChannelAdminService.class);

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        StandaloneMockMvcBuilder builder =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                new NfyAdminChannelController(channelAdminService))
                        .setControllerAdvice(new NfyExceptionHandler())
                        .setValidator(ControllerTestSupport.validator());
        mvc = ControllerTestSupport.applyRuntimeContext(builder).build();
    }

    @Test
    void list_returns_envelope_with_tenant_scoped_service_call() throws Exception {
        when(channelAdminService.list(1L)).thenReturn(Map.of("list", List.of()));

        mvc.perform(get("/nfy/api/v1/admin/channels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(channelAdminService).list(1L); // tenant 来自 token claim
    }

    @Test
    void register_binds_snake_case_body_and_envelope() throws Exception {
        when(channelAdminService.register(eq(1L), any(PostChannelsRequest.class)))
                .thenReturn(Map.of("channel_id", "9"));

        mvc.perform(post("/nfy/api/v1/admin/channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channel_type":"DINGTALK","name":"值班群","target":"https://oapi.dingtalk.com/robot/send?access_token=t","keyword":"OPS"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channel_id").value("9"));

        ArgumentCaptor<PostChannelsRequest> cap = ArgumentCaptor.forClass(PostChannelsRequest.class);
        verify(channelAdminService).register(eq(1L), cap.capture());
        assertThat(cap.getValue().channelType()).isEqualTo("DINGTALK");
        assertThat(cap.getValue().name()).isEqualTo("值班群");
        assertThat(cap.getValue().keyword()).isEqualTo("OPS");
    }

    @Test
    void register_missing_required_fields_is_400_without_service_call() throws Exception {
        mvc.perform(post("/nfy/api/v1/admin/channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"只有名字\"}"))
                .andExpect(status().isBadRequest()); // channelType/target @NotBlank
        org.mockito.Mockito.verifyNoInteractions(channelAdminService);
    }

    @Test
    void verify_and_delete_forward_channel_id_path_variable() throws Exception {
        when(channelAdminService.verify(1L, "9")).thenReturn(Map.of("status", "ENABLED"));
        mvc.perform(post("/nfy/api/v1/admin/channels/9/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ENABLED"));

        when(channelAdminService.delete(1L, "9")).thenReturn(Map.of("deleted", true));
        mvc.perform(delete("/nfy/api/v1/admin/channels/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));
    }

    @Test
    void patch_binds_status_field_to_path_bound_channel() throws Exception {
        when(channelAdminService.patch(eq(1L), eq("9"), any(PatchChannelsChannelIdRequest.class)))
                .thenReturn(Map.of("status", "DISABLED"));

        mvc.perform(patch("/nfy/api/v1/admin/channels/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\",\"name\":\"巡检群\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        ArgumentCaptor<PatchChannelsChannelIdRequest> cap =
                ArgumentCaptor.forClass(PatchChannelsChannelIdRequest.class);
        verify(channelAdminService).patch(eq(1L), eq("9"), cap.capture());
        assertThat(cap.getValue().status()).isEqualTo("DISABLED");
        assertThat(cap.getValue().name()).isEqualTo("巡检群");
    }

    @Test
    void route_guards_are_declared_via_annotations() {
        RequiresToken token = NfyAdminChannelController.class.getAnnotation(RequiresToken.class);
        assertThat(token).isNotNull();
        assertThat(token.value()).isEqualTo("TENANT");
        assertThat(NfyAdminChannelController.class.getAnnotation(TenantDomain.class)).isNotNull();
    }
}
