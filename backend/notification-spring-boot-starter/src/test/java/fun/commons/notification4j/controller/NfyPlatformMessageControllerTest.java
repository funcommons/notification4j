package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.PlatformDomain;
import fun.commons.notification4j.service.PlatformMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台站内信查询面切片单测：NfyPlatformMessageController（API-PPM-001/002）。
 * 与 PAN 同构：standalone 无 framework 拦截器链 → 平台域不依赖 TokenContext/X-User-Id
 * （同 NfyPlatformTenantControllerTest 口径）；@RequestParam 显式名绑定（user_id/type_code/
 * created_from/created_to）+ offset/limit 透传 + {message_id} 路由 + 注解守卫存在性。
 */
// VECTOR: TAG=platform-message-unit
class NfyPlatformMessageControllerTest {

    private final PlatformMessageService platformMessageService = mock(PlatformMessageService.class);

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new NfyPlatformMessageController(platformMessageService))
                .setControllerAdvice(new NfyExceptionHandler())
                .setValidator(ControllerTestSupport.validator())
                .setMessageConverters(ControllerTestSupport.snakeCaseJsonConverter())
                .build();
    }

    @Test
    void list_binds_filters_and_forwards_with_pagination() throws Exception {
        when(platformMessageService.list("u-1", "WORK_FAILED", 1780000000000L, 1790000000000L,
                "失败", 20, 50)).thenReturn(Map.of("list", List.of(), "total", 0));

        mvc.perform(get("/nfy/platform/api/v1/messages")
                        .param("user_id", "u-1")
                        .param("type_code", "WORK_FAILED")
                        .param("created_from", "1780000000000")
                        .param("created_to", "1790000000000")
                        .param("keyword", "失败")
                        .param("offset", "20")
                        .param("limit", "50")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0));

        verify(platformMessageService).list("u-1", "WORK_FAILED", 1780000000000L, 1790000000000L, "失败", 20, 50);
    }

    @Test
    void list_without_filters_omits_all_optional_params() throws Exception {
        when(platformMessageService.list(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(Map.of("list", List.of(Map.of("message_id", "7")), "total", 1));

        mvc.perform(get("/nfy/platform/api/v1/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].message_id").value("7"));

        verify(platformMessageService).list(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void detail_forwards_message_id_path_variable() throws Exception {
        when(platformMessageService.detail("42")).thenReturn(Map.of("message_id", "42", "read_count", 1L));

        mvc.perform(get("/nfy/platform/api/v1/messages/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.message_id").value("42"))
                .andExpect(jsonPath("$.data.read_count").value(1));

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(platformMessageService).detail(cap.capture());
        assertThat(cap.getValue()).isEqualTo("42");
    }

    @Test
    void route_guards_are_declared_via_annotations() {
        RequiresToken token = NfyPlatformMessageController.class.getAnnotation(RequiresToken.class);
        assertThat(token).isNotNull();
        assertThat(token.value()).isEqualTo("TENANT");
        assertThat(NfyPlatformMessageController.class.getAnnotation(PlatformDomain.class)).isNotNull();
    }

    @Test
    void platform_route_is_isolated_from_runtime_face() {
        String mapping = NfyPlatformMessageController.class
                .getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class).value()[0];
        assertThat(mapping).isEqualTo("/nfy/platform/api/v1/messages");
        assertThat(mapping).startsWith("/nfy/platform/");
    }
}
