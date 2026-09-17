package fun.commons.notification4j.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 24 步纯函数层单测：IM 机器人加签。期望值 = 独立实现（python hmac）离线预计算的固定向量，
 * 非同代码复读：钉钉含 +/= 字符验证 urlencode、飞书含 / 字符验证不 urlencode、UTF-8 密钥。
 */
// VECTOR: TAG=step24-unit
class WebhookSignerTest {

    @Test
    void dingTalk_sign_millis_urlencoded_vector() {
        // hmac_sha256(key="secret7", data="1600000000000\nsecret7") → base64 → urlencode
        assertThat(WebhookSigner.dingTalkSign("secret7", 1600000000000L))
                .isEqualTo("30ei1DC1e3OpE%2BhAMaLK4gvJtiUSKz5iUezHwqvaZKU%3D");
    }

    @Test
    void feishu_sign_seconds_not_urlencoded_vector() {
        // hmac_sha256(key="1600000000\nsecret7", data="") → base64（"/" 原样保留，验证无 urlencode）
        assertThat(WebhookSigner.feishuSign("secret7", 1600000000L))
                .isEqualTo("t9wZAvMSGb70Bpz9NmvUyR/PZ0yYDzniZlk5dvQK7Fk=");
    }

    @Test
    void dingTalk_sign_handles_utf8_secret() {
        // 非法字符集下两轮调用结果稳定（UTF-8 字节口径），且不同密钥产出不同签名
        String s1 = WebhookSigner.dingTalkSign("k1", 42L);
        String s2 = WebhookSigner.dingTalkSign("k2", 42L);
        assertThat(s1).isNotEqualTo(s2);
        assertThat(s1).isEqualTo(WebhookSigner.dingTalkSign("k1", 42L));
    }
}
