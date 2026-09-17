package fun.commons.notification4j.remote;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * HMAC-SHA256 签名串构造与签名（client-starter 本地化版本）。
 *
 * <p>线协议与 framework4j-signature 的 {@code SignatureUtil} 逐字节一致（mc-java-security §6）：
 * <ul>
 *   <li>签名串 = METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + NONCE + "\n" + BODY_MD5</li>
 *   <li>签名值 = BASE64(HMAC_SHA256(secret, 签名串))</li>
 * </ul>
 * 本地化的理由：framework4j-signature 传递依赖 framework4j-web/redis（redisson 等），
 * 违背 client-starter「零数据面/零中间件」定位；四元组头（X-Access-Key/X-Timestamp/X-Nonce/X-Signature）
 * 已是冻结线协议，服务端验签侧不感知客户端实现。防漂移：notification4j-it 的
 * {@code NfyClientStarterTest} 以真实 {@code SignatureUtil} 做逐字节等价回归。
 */
public final class NfyHmacSigner {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private NfyHmacSigner() {
    }

    /**
     * 构造签名串
     *
     * @param method      HTTP 方法（GET/POST/...）
     * @param path        请求路径（不含 query string）
     * @param timestamp   Unix 毫秒
     * @param nonce       UUID v4
     * @param bodyMd5Hex  请求体 MD5 十六进制（空 body 为 MD5 of empty string）
     */
    public static String buildStringToSign(String method, String path, String timestamp,
                                           String nonce, String bodyMd5Hex) {
        return method + "\n"
                + path + "\n"
                + timestamp + "\n"
                + nonce + "\n"
                + bodyMd5Hex;
    }

    /**
     * 计算签名（BASE64）。客户端调用频度 = HTTP 出呼频度，逐次 getInstance 的 JCA 开销可忽略，
     * 不做 ThreadLocal Mac 缓存（少一份状态，轻量面更小）。
     *
     * @param secret       HMAC 密钥
     * @param stringToSign 签名串
     */
    public static String sign(String secret, String stringToSign) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] hmac = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hmac);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 compute failed", e);
        }
    }
}
