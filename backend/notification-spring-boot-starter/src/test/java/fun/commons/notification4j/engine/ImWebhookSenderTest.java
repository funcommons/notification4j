package fun.commons.notification4j.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.notification4j.entity.NfyaChannel;
import fun.commons.notification4j.entity.NfyaDelivery;
import fun.commons.notification4j.util.WebhookSigner;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 第 24 步纯函数层单测：ImWebhookSender 签名构造与发送判定（mock RestTemplate，零网络）。
 * 加签口径断言：钉钉毫秒 timestamp+urlencode 签名拼 query（有 ? 用 & 前缀）、飞书秒级
 * timestamp+sign 入 body、企微 target 原样无签。sign 一致性用发送时刻捕获的 timestamp 重算
 * WebhookSigner 对照（无 sleep）；成功判定 = 业务码 errcode/code=0；异常/非 2xx/超长摘要分支。
 */
// VECTOR: TAG=step24-unit
class ImWebhookSenderTest {

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final ImWebhookSender sender = new ImWebhookSender(restTemplate, new ObjectMapper());

    private static NfyaDelivery delivery(String title) {
        NfyaDelivery d = new NfyaDelivery();
        d.setTitle(title);
        return d;
    }

    private static NfyaChannel channel(String type, String target, String secret, String keyword) {
        NfyaChannel ch = new NfyaChannel();
        ch.setId(7L);
        ch.setChannelType(type);
        ch.setTarget(target);
        ch.setSecret(secret);
        ch.setKeyword(keyword);
        return ch;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOf(ArgumentCaptor<Object> captor) {
        return (Map<String, Object>) ((HttpEntity<?>) captor.getValue()).getBody();
    }

    // ---- supports/channelType ----

    @Test
    void supports_three_im_types_only() {
        assertThat(sender.channelType()).isEqualTo("IM");
        assertThat(sender.supports("DINGTALK")).isTrue();
        assertThat(sender.supports("WECOM")).isTrue();
        assertThat(sender.supports("FEISHU")).isTrue();
        assertThat(sender.supports("EMAIL")).isFalse();
        assertThat(sender.supports("INAPP")).isFalse();
    }

    // ---- 钉钉：毫秒 key=secret，query 携带 ----

    @Test
    void dingtalk_appends_millisecond_timestamp_and_urlencoded_sign_to_existing_query() {
        String target = "https://oapi.dingtalk.com/robot/send?access_token=t1";
        when(restTemplate.postForEntity(anyString(), any(Object.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"errcode\":0,\"errmsg\":\"ok\"}"));
        long before = System.currentTimeMillis();
        sender.send(delivery("Disk full"), channel("DINGTALK", target, "SECret", "OPS"));
        long after = System.currentTimeMillis();

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> entity = ArgumentCaptor.forClass(Object.class);
        verify(restTemplate).postForEntity(url.capture(), entity.capture(), eq(String.class));

        assertThat(url.getValue()).startsWith(target + "&timestamp=");
        long ts = Long.parseLong(url.getValue().replaceAll(".*timestamp=(\\d+)&sign=.*", "$1"));
        assertThat(ts).isBetween(before, after); // 毫秒口径
        String sign = url.getValue().replaceAll(".*&sign=(.*)$", "$1");
        assertThat(sign).isEqualTo(WebhookSigner.dingTalkSign("SECret", ts)); // urlencode 后的签名

        Map<String, Object> body = bodyOf(entity);
        assertThat(body.get("msgtype")).isEqualTo("text");
        assertThat(body.get("text")).isEqualTo(Map.of("content", "OPS Disk full")); // keyword 前缀
        HttpEntity<?> httpEntity = (HttpEntity<?>) entity.getValue();
        assertThat(httpEntity.getHeaders().getContentType()).isEqualTo(org.springframework.http.MediaType.APPLICATION_JSON);
    }

    @Test
    void dingtalk_without_query_uses_question_mark_and_without_secret_stays_unsigned() {
        NfyaChannel ch = channel("DINGTALK", "https://oapi.dingtalk.com/robot/send", "", null);
        when(restTemplate.postForEntity(anyString(), any(Object.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"errcode\":0}"));

        sender.send(delivery("t"), ch);

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).postForEntity(url.capture(), any(Object.class), eq(String.class));
        assertThat(url.getValue()).isEqualTo("https://oapi.dingtalk.com/robot/send"); // 空 secret → 不加签
    }

    // ---- 企微：key 即鉴权，无签名 ----

    @Test
    void wecom_posts_target_verbatim_even_with_secret() {
        when(restTemplate.postForEntity(anyString(), any(Object.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"errcode\":0}"));
        NfyaChannel ch = channel("WECOM", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=k2", "ignored", null);

        ChannelSender.SendResult result = sender.send(delivery("Hello"), ch);

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> entity = ArgumentCaptor.forClass(Object.class);
        verify(restTemplate).postForEntity(url.capture(), entity.capture(), eq(String.class));
        assertThat(url.getValue()).isEqualTo("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=k2"); // 无 timestamp/sign
        assertThat(bodyOf(entity)).isEqualTo(Map.of("msgtype", "text", "text", Map.of("content", "Hello")));
        assertThat(result.ok()).isTrue();
        assertThat(result.summary()).isEqualTo("ok");
    }

    // ---- 飞书：秒级 body 携带 ----

    @Test
    void feishu_puts_second_timestamp_and_sign_in_body() {
        when(restTemplate.postForEntity(anyString(), any(Object.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"code\":0}"));
        NfyaChannel ch = channel("FEISHU", "https://open.feishu.cn/hook/tok", "SECret", null);
        long before = System.currentTimeMillis();

        ChannelSender.SendResult result = sender.send(delivery("Feishu msg"), ch);

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> entity = ArgumentCaptor.forClass(Object.class);
        verify(restTemplate).postForEntity(url.capture(), entity.capture(), eq(String.class));
        assertThat(url.getValue()).isEqualTo("https://open.feishu.cn/hook/tok"); // URL 无拼签

        Map<String, Object> body = bodyOf(entity);
        assertThat(body.get("msg_type")).isEqualTo("text");
        assertThat(body.get("content")).isEqualTo(Map.of("text", "Feishu msg"));
        long ts = Long.parseLong((String) body.get("timestamp"));
        assertThat(ts).isBetween(before / 1000, System.currentTimeMillis() / 1000 + 1); // 秒级口径
        assertThat(body.get("sign")).isEqualTo(WebhookSigner.feishuSign("SECret", ts));
        assertThat(result.ok()).isTrue(); // code=0（飞书口径）
    }

    @Test
    void feishu_without_secret_has_no_timestamp_or_sign() {
        when(restTemplate.postForEntity(anyString(), any(Object.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"code\":0}"));
        NfyaChannel ch = channel("FEISHU", "https://open.feishu.cn/hook/tok", null, " ");

        sender.send(delivery(null), ch); // title null + keyword blank → 缺省文案

        ArgumentCaptor<Object> entity = ArgumentCaptor.forClass(Object.class);
        verify(restTemplate).postForEntity(anyString(), entity.capture(), eq(String.class));
        Map<String, Object> body = bodyOf(entity);
        assertThat(body).doesNotContainKeys("timestamp", "sign");
        assertThat(body.get("content")).isEqualTo(Map.of("text", "notification4j 通知"));
    }

    // ---- 不支持类型 ----

    @Test
    void unsupported_channel_type_fails_without_http_call() {
        ChannelSender.SendResult result = sender.send(delivery("t"), channel("SMS", "https://x/y", null, null));
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).isEqualTo("不支持的渠道类型: SMS");
        verifyNoInteractions(restTemplate);
    }

    // ---- 业务码判定与异常/摘要分支 ----

    @Test
    void errcode_nonzero_fails_with_raw_summary() {
        when(restTemplate.postForEntity(anyString(), any(Object.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"errcode\":310000,\"errmsg\":\"sign not match\"}"));
        ChannelSender.SendResult result = sender.send(delivery("t"),
                channel("DINGTALK", "https://oapi.dingtalk.com/x", null, null));
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).isEqualTo("{\"errcode\":310000,\"errmsg\":\"sign not match\"}");
    }

    @Test
    void non_json_body_counts_as_failure() {
        when(restTemplate.postForEntity(anyString(), any(Object.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("not-json"));
        ChannelSender.SendResult result = sender.send(delivery("t"),
                channel("DINGTALK", "https://oapi.dingtalk.com/x", null, null));
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).isEqualTo("not-json");
    }

    @Test
    void blank_or_null_body_counts_as_failure_with_placeholder_summary() {
        when(restTemplate.postForEntity(anyString(), any(Object.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(""), ResponseEntity.ok((String) null));
        ChannelSender.SendResult first = sender.send(delivery("t"),
                channel("DINGTALK", "https://oapi.dingtalk.com/x", null, null));
        ChannelSender.SendResult second = sender.send(delivery("t"),
                channel("DINGTALK", "https://oapi.dingtalk.com/x", null, null));
        assertThat(first.ok()).isFalse();
        assertThat(first.summary()).isEqualTo("无响应摘要");
        assertThat(second.ok()).isFalse();
        assertThat(second.summary()).isEqualTo("无响应摘要");
    }

    @Test
    void overlong_failure_body_is_truncated_to_120_chars() {
        String longBody = "x".repeat(200);
        when(restTemplate.postForEntity(anyString(), any(Object.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(longBody));
        ChannelSender.SendResult result = sender.send(delivery("t"),
                channel("DINGTALK", "https://oapi.dingtalk.com/x", null, null));
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).hasSize(120);
    }

    @Test
    void http_status_error_returns_http_prefix_summary() {
        when(restTemplate.postForEntity(anyString(), any(Object.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "403",
                        new HttpHeaders(), new byte[0], null));
        ChannelSender.SendResult result = sender.send(delivery("t"),
                channel("DINGTALK", "https://oapi.dingtalk.com/x", null, null));
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).isEqualTo("HTTP 403");
    }

    @Test
    void connection_failure_returns_generic_summary_without_target_leak() {
        when(restTemplate.postForEntity(anyString(), any(Object.class), eq(String.class)))
                .thenThrow(new RuntimeException("connect timed out"));
        ChannelSender.SendResult result = sender.send(delivery("t"),
                channel("DINGTALK", "https://oapi.dingtalk.com/x", null, null));
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).isEqualTo("渠道无响应或连接失败");
        assertThat(result.summary()).doesNotContain("oapi.dingtalk.com");
    }

    @Test
    void non_2xx_response_entity_counts_as_failure() {
        when(restTemplate.postForEntity(anyString(), any(Object.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(502).body("{\"errcode\":0}"));
        ChannelSender.SendResult result = sender.send(delivery("t"),
                channel("DINGTALK", "https://oapi.dingtalk.com/x", null, null));
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).isEqualTo("HTTP 502");
    }
}
