package fun.commons.notification4j.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.notification4j.properties.NfyProperties;
import fun.commons.framework4j.accesstoken.core.AccessTokenGenerator;
import fun.commons.framework4j.signature.util.SignatureUtil;
import fun.commons.framework4j.transport.HttpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 带 S2S JWT + HMAC 签名的 HttpTransport 装饰器。
 * <p>
 * 包装底层 {@link HttpTransport} (如 RestTemplateHttpTransport), post/get/put/delete 前自动注入:
 * - Authorization: Bearer S2S JWT (AccessTokenGenerator.generateToken, 可选)
 * - X-Access-Key / X-Timestamp / X-Nonce / X-Signature (HMAC-SHA256)
 * <p>
 * 业务方 remote 模式需配 notification4j.runtime.remote-tenant-id + remote-tenant-secret
 * (从独立部署 notification4j 平台获取)。S2S JWT 需业务方配 framework4j-access-token (Redis)。
 */
public class AuthenticatedHttpTransport implements HttpTransport {

    private static final Logger log = LoggerFactory.getLogger(AuthenticatedHttpTransport.class);

    private final HttpTransport delegate;
    private final ObjectProvider<AccessTokenGenerator> tokenGeneratorProvider;
    private final NfyProperties properties;
    private final ObjectMapper objectMapper;

    public AuthenticatedHttpTransport(HttpTransport delegate,
                                      ObjectProvider<AccessTokenGenerator> tokenGeneratorProvider,
                                      NfyProperties properties,
                                      ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.tokenGeneratorProvider = tokenGeneratorProvider;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Object post(String url, Object body, Map<String, String> headers) {
        return delegate.post(url, body, enrich(url, "POST", body, headers));
    }

    @Override
    public Object get(String url, Map<String, String> headers) {
        return delegate.get(url, enrich(url, "GET", null, headers));
    }

    @Override
    public Object put(String url, Object body, Map<String, String> headers) {
        return delegate.put(url, body, enrich(url, "PUT", body, headers));
    }

    @Override
    public Object delete(String url, Map<String, String> headers) {
        return delegate.delete(url, enrich(url, "DELETE", null, headers));
    }

    /**
     * 注入 S2S JWT + HMAC 签名头。
     * 签名串 = METHOD\nPATH\nTS\nNONCE\nBODY_MD5 (SignatureUtil.buildStringToSign)
     * PATH: 从 url 去掉 remoteUrl 前缀 + 去 query string
     */
    private Map<String, String> enrich(String url, String method, Object body, Map<String, String> existing) {
        // RemoteNotifyClient 四个动词均传 null headers（评审第 26 步 P1：直接 new HashMap<>(null) 会 NPE，
        // remote 模式生产组合下首个请求即崩；null 归一为空 map）
        Map<String, String> h = new HashMap<>(existing == null ? Map.of() : existing);

        String tenantId = properties.getRemoteTenantId();
        String tenantSecret = properties.getRemoteTenantSecret();
        if (tenantId == null || tenantSecret == null) {
            log.warn("[AuthenticatedHttpTransport] remote-tenant-id/secret 未配, 跳过签名 (远端 @RequiresToken 会拒)");
            return h;
        }

        // S2S JWT (可选)
        AccessTokenGenerator gen = tokenGeneratorProvider.getIfAvailable();
        if (gen != null) {
            try {
                String jwt = gen.generateToken("SERVICE", Map.of("tenant_id", tenantId));
                h.put("Authorization", "Bearer " + jwt);
            } catch (Exception e) {
                log.warn("[AuthenticatedHttpTransport] S2S JWT 生成失败 (业务方需配 framework4j-access-token + Redis): {}", e.getMessage());
            }
        }

        // HMAC 签名
        String path = extractPath(url);
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String bodyMd5 = md5Hex(body);
        String sts = SignatureUtil.buildStringToSign(method, path, ts, nonce, bodyMd5);
        String sig = SignatureUtil.sign(tenantSecret, sts);

        h.put("X-Access-Key", tenantId);
        h.put("X-Timestamp", ts);
        h.put("X-Nonce", nonce);
        h.put("X-Signature", sig);
        return h;
    }

    private String extractPath(String url) {
        String remoteUrl = properties.getRemoteUrl();
        String path = (remoteUrl != null && url.startsWith(remoteUrl))
                ? url.substring(remoteUrl.length()) : url;
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(0, q) : path;
    }

    private String md5Hex(Object body) {
        try {
            String json = body == null ? "" : objectMapper.writeValueAsString(body);
            byte[] md5 = MessageDigest.getInstance("MD5").digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : md5) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
