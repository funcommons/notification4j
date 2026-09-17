package fun.commons.notification4j.controller;

import fun.commons.notification4j.dto.PatchTypesRequest;
import fun.commons.notification4j.entity.NfyaMessageType;
import fun.commons.notification4j.service.MessageTypeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 28 步 controller 覆盖缺口补齐：NfyAdminTypeController（API-TYP-002 修改类型）。
 * 缺口行为 update 体（合并报告 L51~54）：PATCH /{type_id} 薄委托——路由/@Valid/tenantId
 * 取 TokenContext 透传/信封（type_id 出网恒字符串）。create/list 由 IT 覆盖（100% 已含）。
 */
// VECTOR: TAG=step28-unit
class NfyAdminTypeControllerTest {

    private final MessageTypeService messageTypeService = mock(MessageTypeService.class);

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        StandaloneMockMvcBuilder builder = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new NfyAdminTypeController(messageTypeService))
                .setControllerAdvice(new NfyExceptionHandler())
                .setValidator(ControllerTestSupport.validator());
        mvc = ControllerTestSupport.applyRuntimeContext(builder).build(); // 租户域：TokenContext tenant_id=1
    }

    @Test
    void update_forwards_tenant_and_type_id_and_returns_envelope() throws Exception {
        NfyaMessageType t = new NfyaMessageType();
        t.setId(9L);
        t.setStatus("DISABLED");
        when(messageTypeService.update(eq(1L), eq(9L), any(PatchTypesRequest.class))).thenReturn(t);

        mvc.perform(patch("/nfy/api/v1/admin/types/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新名\",\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.type_id").value("9"))
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
        verify(messageTypeService).update(eq(1L), eq(9L), any(PatchTypesRequest.class));
    }

    @Test
    void update_blank_name_violates_size_and_skips_service() throws Exception {
        mvc.perform(patch("/nfy/api/v1/admin/types/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"status\":\"ENABLED\"}"))
                .andExpect(status().isBadRequest()); // @Size(min=1)
        verifyNoInteractions(messageTypeService);
    }
}
