package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.exception.AuthException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * 第 26 步 controller 层切片单测：NfyExceptionHandler（@RestControllerAdvice @Order 最高优先）。
 * AuthException → HTTP 200 + 信封 code=AuthException.code（§2.2 认证失败统一 HTTP 200 信封，
 * 否则 fwk-web 兜底会落 500）；SecurityException → HTTP 403 + 信封 code=403
 * （tracelog 控制台等非 token 体系鉴权）。探针控制器只抛异常，纯映射面测试。
 */
// VECTOR: TAG=step26-unit
class NfyExceptionHandlerTest {

    @RestController
    static class ThrowingProbe {

        @GetMapping("/probe/auth")
        public String auth() {
            throw new AuthException(10207, "token 型别不匹配");
        }

        @GetMapping("/probe/security")
        public String security() {
            throw new SecurityException("console denied");
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new ThrowingProbe(), new OtherProbe())
                .setControllerAdvice(new NfyExceptionHandler())
                .build();
    }

    @Test
    void auth_exception_maps_to_http_200_envelope_with_original_code() throws Exception {
        mvc.perform(get("/probe/auth"))
                .andExpect(status().isOk()) // 认证失败统一 HTTP 200 信封（防 fwk 兜底 500）
                .andExpect(jsonPath("$.code").value(10207))
                .andExpect(jsonPath("$.message").value("token 型别不匹配"));
    }

    @Test
    void security_exception_maps_to_http_403_with_fixed_envelope() throws Exception {
        mvc.perform(get("/probe/security"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("权限不足")); // 固定文案，不透出内部原因
    }

    @Test
    void unhandled_exception_is_not_swallowed_by_this_advice() {
        // 非 Auth/Security 异常不由本 advice 处理（交 fwk-web GlobalExceptionHandler）：
        // standalone 无其他 advice → 异常原样冒泡出 perform（不落 200 信封、不吞）
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        mvc.perform(get("/probe/other")))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @RestController
    static class OtherProbe {
        @GetMapping("/probe/other")
        public String other() {
            throw new IllegalStateException("boom");
        }
    }
}
