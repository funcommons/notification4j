package fun.commons.notification4j.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.accesstoken.core.AccessTokenGenerator;
import fun.commons.framework4j.signature.util.SignatureUtil;
import fun.commons.framework4j.transport.HttpTransport;
import fun.commons.notification4j.properties.NfyProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 26 步 transport 层单测：AuthenticatedHttpTransport 装饰器（mock 委托 transport，零网络）。
 * 断言面：post/get/put/delete 四动词均经 enrich 后透传 / S2S JWT 注入（generator 有→Bearer、
 * 无或抛→仅签名不拦请求）/ HMAC 签名四元组（X-Access-Key/X-Timestamp/X-Nonce/X-Signature，
 * 签名以捕获的 ts/nonce 按 SignatureUtil 同口径重算对照）/ 签名 PATH = 去 remoteUrl 前缀 +
 * 去 query / 未配租户凭据时跳过签名且不改写调用方 headers（防御性复制）。
 */
// VECTOR: TAG=step26-unit
class AuthenticatedHttpTransportTest {

    private final HttpTransport delegate = mock(HttpTransport.class);
    private final NfyProperties properties = new NfyProperties();

    @SuppressWarnings("unchecked")
    private AuthenticatedHttpTransport newTransport(AccessTokenGenerator generator) {
        ObjectProvider<AccessTokenGenerator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(generator);
        return new AuthenticatedHttpTransport(delegate, provider, properties, new ObjectMapper());
    }

    /** 以捕获的 ts/nonce/body 按 SignatureUtil 同口径重算签名（对照断言，无 sleep） */
    private String expectedSignature(String method, String path, String ts, String nonce, Object body) {
        String bodyMd5 = md5Hex(body);
        String sts = SignatureUtil.buildStringToSign(method, path, ts, nonce, bodyMd5);
        return SignatureUtil.sign("secret-1", sts);
    }

    private static String md5Hex(Object body) {
        try {
            String json = body == null ? "" : new ObjectMapper().writeValueAsString(body);
            byte[] md5 = MessageDigest.getInstance("MD5").digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : md5) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void signedHeaders() {
        properties.setRemoteTenantId("tenant-1");
        properties.setRemoteTenantSecret("secret-1");
        properties.setRemoteUrl("http://notification4j-svc:8080");
    }

    @Test
    void post_injects_jwt_and_hmac_signature_headers_with_sts_recomputed() {
        signedHeaders();
        AccessTokenGenerator generator = mock(AccessTokenGenerator.class);
        when(generator.generateToken(eq("SERVICE"), any())).thenReturn("jwt-abc");
        AuthenticatedHttpTransport transport = newTransport(generator);
        Map<String, Object> body = Map.of("type_code", "OTC");

        transport.post("http://notification4j-svc:8080/nfy/api/v1/runtime/messages", body, Map.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> hCap = ArgumentCaptor.forClass(Map.class);
        verify(delegate).post(eq("http://notification4j-svc:8080/nfy/api/v1/runtime/messages"),
                eq(body), hCap.capture());
        Map<String, String> h = hCap.getValue();
        assertThat(h.get("Authorization")).isEqualTo("Bearer jwt-abc");
        assertThat(h.get("X-Access-Key")).isEqualTo("tenant-1");
        long ts = Long.parseLong(h.get("X-Timestamp"));
        assertThat(ts).isStrictlyBetween(System.currentTimeMillis() - 60_000,
                System.currentTimeMillis() + 60_000);
        assertThat(h.get("X-Nonce")).isNotBlank();
        // 签名 = HMAC(secret, method\npath(去前缀去query)\nts\nnonce\nbody_md5)
        assertThat(h.get("X-Signature")).isEqualTo(expectedSignature(
                "POST", "/nfy/api/v1/runtime/messages", h.get("X-Timestamp"), h.get("X-Nonce"), body));
    }

    @Test
    void get_signature_uses_empty_body_md5_and_strips_query_string() {
        signedHeaders();
        AuthenticatedHttpTransport transport = newTransport(null); // 无 generator → 仅签名

        transport.get("http://notification4j-svc:8080/nfy/api/v1/runtime/messages?cursor=c1&limit=5", Map.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> hCap = ArgumentCaptor.forClass(Map.class);
        verify(delegate).get(anyString(), hCap.capture());
        Map<String, String> h = hCap.getValue();
        assertThat(h).doesNotContainKey("Authorization"); // generator 缺席 → 无 JWT，但签名仍在
        assertThat(h.get("X-Signature")).isEqualTo(expectedSignature(
                "GET", "/nfy/api/v1/runtime/messages", h.get("X-Timestamp"), h.get("X-Nonce"), null));
    }

    @Test
    void put_and_delete_are_enriched_and_forwarded() {
        signedHeaders();
        AuthenticatedHttpTransport transport = newTransport(null);

        transport.put("http://notification4j-svc:8080/nfy/api/v1/x", Map.of("a", 1), Map.of());
        transport.delete("http://notification4j-svc:8080/nfy/api/v1/x", Map.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> h1 = ArgumentCaptor.forClass(Map.class);
        verify(delegate).put(anyString(), any(), h1.capture());
        assertThat(h1.getValue()).containsKeys("X-Access-Key", "X-Signature");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> h2 = ArgumentCaptor.forClass(Map.class);
        verify(delegate).delete(anyString(), h2.capture());
        assertThat(h2.getValue()).containsKeys("X-Access-Key", "X-Signature");
    }

    @Test
    void missing_tenant_credentials_skip_signing_and_keep_caller_headers_unmodified() {
        // 未配 remote-tenant-id/secret
        AuthenticatedHttpTransport transport = newTransport(null);
        Map<String, String> callerHeaders = new HashMap<>();
        callerHeaders.put("X-Custom", "keep");

        transport.post("/nfy/api/v1/runtime/messages", Map.of("a", 1), callerHeaders);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> hCap = ArgumentCaptor.forClass(Map.class);
        verify(delegate).post(anyString(), any(), hCap.capture());
        assertThat(hCap.getValue()).doesNotContainKeys("X-Access-Key", "X-Signature", "Authorization");
        assertThat(hCap.getValue()).containsEntry("X-Custom", "keep"); // 原有 headers 保留
        assertThat(callerHeaders).containsOnlyKeys("X-Custom"); // 调用方 map 不被改写（防御性复制）
    }

    @Test
    void null_headers_with_credentials_configured_do_not_npe_and_still_sign() {
        // 评审第 26 步 P1 回归：RemoteNotifyClient 四动词均传 null headers，
        // 凭据已配（autoconfig 必装本装饰器）时 enrich 首行曾 new HashMap<>(null) 必 NPE
        signedHeaders();
        AuthenticatedHttpTransport transport = newTransport(null);

        transport.post("http://notification4j-svc:8080/nfy/api/v1/runtime/messages",
                Map.of("type_code", "OTC"), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> hCap = ArgumentCaptor.forClass(Map.class);
        verify(delegate).post(anyString(), any(), hCap.capture());
        Map<String, String> h = hCap.getValue();
        assertThat(h.get("X-Access-Key")).isEqualTo("tenant-1");
        assertThat(h.get("X-Signature")).isEqualTo(expectedSignature(
                "POST", "/nfy/api/v1/runtime/messages", h.get("X-Timestamp"), h.get("X-Nonce"),
                Map.of("type_code", "OTC")));
    }

    @Test
    void token_generator_failure_degrades_to_signature_only_without_blocking_request() {
        signedHeaders();
        AccessTokenGenerator generator = mock(AccessTokenGenerator.class);
        when(generator.generateToken(anyString(), any())).thenThrow(new IllegalStateException("redis down"));
        AuthenticatedHttpTransport transport = newTransport(generator);

        transport.post("http://notification4j-svc:8080/nfy/api/v1/x", null, Map.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> hCap = ArgumentCaptor.forClass(Map.class);
        verify(delegate).post(anyString(), isNull(), hCap.capture());
        Map<String, String> h = hCap.getValue();
        assertThat(h).doesNotContainKey("Authorization"); // JWT 失败降级
        assertThat(h.get("X-Signature")).isEqualTo(expectedSignature(
                "POST", "/nfy/api/v1/x", h.get("X-Timestamp"), h.get("X-Nonce"), null));
    }

    @Test
    void url_without_remote_prefix_and_null_body_still_signs_deterministically() {
        properties.setRemoteTenantId("tenant-1");
        properties.setRemoteTenantSecret("secret-1");
        properties.setRemoteUrl(null); // 全路径 URL（测试桩场景）
        AuthenticatedHttpTransport transport = newTransport(null);

        transport.get("http://other-svc:9090/nfy/api/v1/y", Map.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> hCap = ArgumentCaptor.forClass(Map.class);
        verify(delegate).get(anyString(), hCap.capture());
        Map<String, String> h = hCap.getValue();
        assertThat(h.get("X-Signature")).isEqualTo(expectedSignature(
                "GET", "http://other-svc:9090/nfy/api/v1/y", h.get("X-Timestamp"), h.get("X-Nonce"), null));
    }
}
