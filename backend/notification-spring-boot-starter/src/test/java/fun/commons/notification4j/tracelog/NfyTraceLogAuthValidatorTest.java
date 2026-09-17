package fun.commons.notification4j.tracelog;

import fun.commons.framework4j.accesstoken.config.AccessTokenProperties;
import fun.commons.framework4j.accesstoken.util.TokenUtils;
import fun.commons.framework4j.tracelog.query.SwitchRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 第 26 步 tracelog 层单测：NfyTraceLogAuthValidator（tracelog 控制台平台运营判定）。
 * 判定口径：无请求上下文 / 无 Bearer 头 / accesstoken 配置缺席 / JWT 解析失败 /
 * hash 不匹配（普通租户 token）→ 一律拒绝；仅「tenant_id=0 合成平台租户」token 的
 * keyHash 命中（HMAC("0", hashSalt) 常量时间比对）才放行三操作（query/switch/export）。
 * TokenUtils 静态方法以 mockStatic 拦截（期望 hash 在开 mock 前用真实现计算对照，
 * mock 域内不再触真方法——静态 mock 未打桩调用返回默认值）。
 */
// VECTOR: TAG=step26-unit
class NfyTraceLogAuthValidatorTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<AccessTokenProperties> propsProvider = mock(ObjectProvider.class);
    private final NfyTraceLogAuthValidator validator = new NfyTraceLogAuthValidator(propsProvider);

    @AfterEach
    void resetRequestScope() {
        RequestContextHolder.resetRequestAttributes(); // ThreadLocal 清理防用例间串扰
    }

    private void givenRequest(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private AccessTokenProperties props(String secretKey, String hashSalt) {
        AccessTokenProperties p = new AccessTokenProperties();
        p.setSecretKey(secretKey);
        p.setHashSalt(hashSalt);
        return p;
    }

    /** mockStatic TokenUtils：parseToken 返回给定 payload；calculateKeyHash 回预计算值（真实现先行算好） */
    private AutoCloseable stubTokenUtils(String bearerToken, Map<String, Object> payload, String platformHash) {
        var mocked = mockStatic(TokenUtils.class);
        mocked.when(() -> TokenUtils.parseToken(bearerToken, "sign-key")).thenReturn(payload);
        mocked.when(() -> TokenUtils.calculateKeyHash(anyString(), anyString())).thenReturn(platformHash);
        return mocked;
    }

    @Test
    void all_three_operations_allowed_only_for_platform_operator_token() throws Exception {
        givenRequest("Bearer platform-token");
        when(propsProvider.getIfAvailable()).thenReturn(props("sign-key", "salt-1"));
        String platformHash = TokenUtils.calculateKeyHash("0", "salt-1"); // mock 前真算：tenant_id=0 合成平台租户
        Map<String, Object> payload = new HashMap<>();
        payload.put("hash", platformHash);
        try (var mocked = stubTokenUtils("platform-token", payload, platformHash)) {
            assertThat(validator.canQuery("op", "1")).isTrue();
            assertThat(validator.canOpenSwitch("op", "1", new SwitchRequest())).isTrue();
            assertThat(validator.canExport("op", "1")).isTrue();
        }
    }

    @Test
    void tenant_token_hash_mismatch_is_rejected_on_all_operations() throws Exception {
        givenRequest("Bearer tenant-token");
        when(propsProvider.getIfAvailable()).thenReturn(props("sign-key", "salt-1"));
        String platformHash = TokenUtils.calculateKeyHash("0", "salt-1");
        Map<String, Object> payload = new HashMap<>();
        payload.put("hash", platformHash + "x"); // 与平台 hash 不等（普通租户 token）
        try (var mocked = stubTokenUtils("tenant-token", payload, platformHash)) {
            assertThat(validator.canQuery("op", "1")).isFalse();
            assertThat(validator.canOpenSwitch("op", "1", new SwitchRequest())).isFalse();
            assertThat(validator.canExport("op", "1")).isFalse();
        }
    }

    @Test
    void no_request_context_or_non_bearer_header_is_rejected_without_parsing() {
        RequestContextHolder.resetRequestAttributes();
        assertThat(validator.canQuery("op", "1")).isFalse(); // 无请求上下文（非 HTTP 线程）

        givenRequest("Basic dXNlcjpwd2Q=");
        assertThat(validator.canExport("op", "1")).isFalse(); // 非 Bearer 头

        givenRequest(null);
        assertThat(validator.canQuery("op", "1")).isFalse(); // 缺 Authorization
    }

    @Test
    void missing_accesstoken_config_or_missing_hash_claim_is_rejected() throws Exception {
        givenRequest("Bearer platform-token");
        when(propsProvider.getIfAvailable()).thenReturn(null);
        assertThat(validator.canQuery("op", "1")).isFalse(); // 业务方未配 framework4j-access-token

        when(propsProvider.getIfAvailable()).thenReturn(props("sign-key", "salt-1"));
        try (var mocked = stubTokenUtils("platform-token", new HashMap<>(), "any-hash")) { // payload 无 hash claim
            assertThat(validator.canExport("op", "1")).isFalse();
        }
    }

    @Test
    void jwt_parse_failure_is_rejected_quietly() {
        givenRequest("Bearer broken-token");
        when(propsProvider.getIfAvailable()).thenReturn(props("sign-key", "salt-1"));
        var mocked = mockStatic(TokenUtils.class);
        mocked.when(() -> TokenUtils.parseToken(anyString(), anyString()))
                .thenThrow(new RuntimeException("bad jwt"));
        try (mocked) {
            assertThat(validator.canQuery("op", "1")).isFalse(); // 解析异常 → 拒绝（不外抛）
        }
    }
}
