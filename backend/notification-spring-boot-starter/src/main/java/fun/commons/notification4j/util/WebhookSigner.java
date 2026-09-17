package fun.commons.notification4j.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * IM 机器人加签（第 6 步发送引擎复用）。
 * 钉钉（官方口径）：sign = urlencode(base64(hmac_sha256(key=secret, data=timestamp毫秒+"\n"+secret)))，query 携带；
 * 飞书：sign = base64(hmac_sha256(key=timestamp秒+"\n"+secret, data=""))，body 携带（不 urlencode）。
 * 企微无加签（key 即鉴权）。
 */
public final class WebhookSigner {

    private WebhookSigner() {
    }

    /** 钉钉：timestamp 毫秒；key=secret，data=ts+"\n"+secret */
    public static String dingTalkSign(String secret, long timestampMillis) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String data = timestampMillis + "\n" + secret;
            return URLEncoder.encode(Base64.getEncoder().encodeToString(
                    mac.doFinal(data.getBytes(StandardCharsets.UTF_8))), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("钉钉加签失败", e);
        }
    }

    /** 飞书：timestamp 秒；key=ts+"\n"+secret，data=空串 */
    public static String feishuSign(String secret, long timestampSeconds) {
        return hmacSha256Base64(timestampSeconds + "\n" + secret, "");
    }

    private static String hmacSha256Base64(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 加签失败", e);
        }
    }
}
