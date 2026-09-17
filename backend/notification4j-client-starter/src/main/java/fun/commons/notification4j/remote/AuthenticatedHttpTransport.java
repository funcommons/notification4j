package fun.commons.notification4j.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.transport.HttpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 带 S2S JWT + HMAC 签名的 HttpTransport 装饰器（编码第 30 步提炼自全量 starter transport 包）。
 * <p>
 * 包装底层 {@link HttpTransport} (如 RestTemplateHttpTransport), post/get/put/delete 前自动注入:
 * - Authorization: Bearer S2S JWT ({@link ServiceTokenSupplier} 可选, null/抛错均降级)
 * - X-Access-Key / X-Timestamp / X-Nonce / X-Signature (HMAC-SHA256, 见 {@link NfyHmacSigner})
 * <p>
 * 业务方 remote 模式需配 nfy.runtime.remote-tenant-id + remote-tenant-secret
 * (从独立部署 notification4j 平台获取)。与全量 starter 的实现差异：token 生成经
 * {@link ServiceTokenSupplier} 函数接口注入（accesstoken 类缺席安全），签名串本地构造
 * （framework4j-signature 会传递 framework4j-web/redis，违背零中间件定位）。
 */
public class AuthenticatedHttpTransport implements HttpTransport {

    private static final Logger log = LoggerFactory.getLogger(AuthenticatedHttpTransport.class);

    private final HttpTransport delegate;
    private final ServiceTokenSupplier tokenSupplier;
    private final NfyClientProperties properties;
    private final ObjectMapper objectMapper;

    public AuthenticatedHttpTransport(HttpTransport delegate,
                                      ServiceTokenSupplier tokenSupplier,
                                      NfyClientProperties properties,
                                      ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.tokenSupplier = tokenSupplier;
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
     * 签名串 = METHOD\nPATH\nTS\nNONCE\nBODY_MD5（NfyHmacSigner.buildStringToSign，
     * 与 framework4j-signature SignatureUtil 逐字节一致）
     * PATH: 从 url 去掉 remoteUrl 前缀 + 去 query string
     */
    private Map<String, String> enrich(String url, String method, Object body, Map<String, String> existing) {
        // null headers 归一为空 map（第 26 步 P1 修复的提炼保留：RemoteNotifyClient 四个动词均传
        // null headers，直接 new HashMap<>(null) 会 NPE，remote 模式生产组合下首个请求即崩）
        Map<String, String> h = new HashMap<>(existing == null ? Map.of() : existing);

        String tenantId = properties.getRemoteTenantId();
        String tenantSecret = properties.getRemoteTenantSecret();
        if (tenantId == null || tenantSecret == null) {
            log.warn("[AuthenticatedHttpTransport] remote-tenant-id/secret 未配, 跳过签名 (远端 @RequiresToken 会拒)");
            return h;
        }

        // S2S JWT (可选：supplier 缺席 = accesstoken 类/Bean 缺席降级路径；抛错同样降级仅签名)
        if (tokenSupplier != null) {
            try {
                String jwt = tokenSupplier.generate(tenantId);
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
        String sts = NfyHmacSigner.buildStringToSign(method, path, ts, nonce, bodyMd5);
        String sig = NfyHmacSigner.sign(tenantSecret, sts);

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
