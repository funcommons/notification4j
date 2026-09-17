package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.TenantDomain;
import fun.commons.notification4j.dto.PostMessagesRequest;
import fun.commons.notification4j.service.AnnouncementService;
import fun.commons.notification4j.service.BatchJobService;
import fun.commons.notification4j.service.MessageService;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 26 步 controller 层切片单测：NfyRuntimeMessageController（MockMvc standaloneSetup，
 * 不起全上下文）。路由映射/参数绑定（query/path/body）/信封封装（ApiResponse code=0 + data）/
 * tenant_id 与 X-User-Id 上下文取值/@Valid 400/鉴权注解存在性（切片无拦截器链，
 * 注解即守卫登记面）。service 全 mock —— 薄委托层的分支即路由本身。
 */
// VECTOR: TAG=step26-unit
class NfyRuntimeMessageControllerTest {

    private final MessageService messageService = mock(MessageService.class);
    private final AnnouncementService announcementService = mock(AnnouncementService.class);
    private final BatchJobService batchJobService = mock(BatchJobService.class);

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        StandaloneMockMvcBuilder builder =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                new NfyRuntimeMessageController(messageService, announcementService, batchJobService))
                        .setControllerAdvice(new NfyExceptionHandler())
                        .setValidator(ControllerTestSupport.validator());
        mvc = ControllerTestSupport.applyRuntimeContext(builder).build();
    }

    @Test
    void send_binds_body_passes_tenant_from_token_and_wraps_envelope() throws Exception {
        when(messageService.send(eq(1L), any(PostMessagesRequest.class))).thenReturn(Map.of(
                "message_id", "777", "biz_no", "BIZ-1", "receiver_count", 2,
                "inapp_saved", true, "delivery_planned", 0));

        mvc.perform(post("/nfy/api/v1/runtime/messages")
                        .header("X-User-Id", "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type_code":"OTC","user_ids":["u1","u2"],"title":"标题","content":"内容","level":"URGENT"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.message_id").value("777"))
                .andExpect(jsonPath("$.data.biz_no").value("BIZ-1"))
                .andExpect(jsonPath("$.data.inapp_saved").value(true));

        ArgumentCaptor<PostMessagesRequest> cap = ArgumentCaptor.forClass(PostMessagesRequest.class);
        verify(messageService).send(eq(1L), cap.capture()); // tenant 来自 token claim（非参数）
        assertThat(cap.getValue().typeCode()).isEqualTo("OTC"); // snake_case JSON → camelCase record
        assertThat(cap.getValue().userIds()).isEqualTo(List.of("u1", "u2"));
        assertThat(cap.getValue().level()).isEqualTo("URGENT");
    }

    @Test
    void send_invalid_body_is_rejected_400_before_service_call() throws Exception {
        mvc.perform(post("/nfy/api/v1/runtime/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type_code":"bad code!","user_ids":[],"title":"","content":"c"}
                                """))
                .andExpect(status().isBadRequest()); // @Valid（type_code 正则/userIds @NotEmpty/title @NotBlank）
        org.mockito.Mockito.verifyNoInteractions(messageService);
    }

    @Test
    void unread_count_aggregates_inapp_and_unconfirmed() throws Exception {
        when(messageService.unreadInappCount(1L, "u1")).thenReturn(3L);
        when(announcementService.unconfirmedCount(1L, "u1")).thenReturn(5L);

        mvc.perform(get("/nfy/api/v1/runtime/messages/unread-count").header("X-User-Id", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.unread_count").value(3))
                .andExpect(jsonPath("$.data.unconfirmed_count").value(5))
                .andExpect(jsonPath("$.data.total").value(8));
    }

    @Test
    void list_binds_all_optional_query_params() throws Exception {
        when(messageService.list(eq(1L), eq("u1"), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("list", List.of(), "has_more", false));

        mvc.perform(get("/nfy/api/v1/runtime/messages")
                        .header("X-User-Id", "u1")
                        .param("type_code", "OTC").param("level", "URGENT")
                        .param("read_status", "UNREAD").param("cursor", "c1").param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.has_more").value(false));

        verify(messageService).list(1L, "u1", "OTC", "URGENT", "UNREAD", "c1", 20); // 五参数全绑定
    }

    @Test
    void recent_passes_limit_and_detail_uses_path_variable() throws Exception {
        when(messageService.recent(eq(1L), eq("u1"), eq(5))).thenReturn(Map.of("list", List.of()));
        mvc.perform(get("/nfy/api/v1/runtime/messages/recent")
                        .header("X-User-Id", "u1").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        when(messageService.detailAndMarkRead(1L, "u1", "555")).thenReturn(Map.of("message_id", "555"));
        mvc.perform(get("/nfy/api/v1/runtime/messages/555").header("X-User-Id", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message_id").value("555")); // {message_id} 路由（字面量子路径共存）
    }

    @Test
    void cancel_delegates_with_tenant_and_message_id() throws Exception {
        when(messageService.cancel(1L, "555")).thenReturn(Map.of("status", "CANCELLED"));

        mvc.perform(post("/nfy/api/v1/runtime/messages/555/cancel")) // T 鉴权端点：无 X-User-Id 也可
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        verify(messageService).cancel(1L, "555");
    }

    @Test
    void batch_send_returns_job_envelope_from_batch_service() throws Exception {
        when(batchJobService.submit(eq(1L), any(PostMessagesRequest.class)))
                .thenReturn(Map.of("job_id", "abc123", "total", 2));

        mvc.perform(post("/nfy/api/v1/runtime/messages/batch")
                        .header("X-User-Id", "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type_code":"OTC","user_ids":["u1","u2"],"title":"t","content":"c"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job_id").value("abc123"));
    }

    @Test
    void route_guards_are_declared_via_annotations() {
        // 切片无拦截器链：守卫以注解存在性为契约（TENANT token + 租户域）
        RequiresToken token = NfyRuntimeMessageController.class.getAnnotation(RequiresToken.class);
        assertThat(token).isNotNull();
        assertThat(token.value()).isEqualTo("TENANT");
        assertThat(NfyRuntimeMessageController.class.getAnnotation(TenantDomain.class)).isNotNull();
    }
}
