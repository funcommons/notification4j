package fun.commons.notification4j.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.entity.NfyaChannel;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 26 步 service 层单测：ChannelVerifier 验证链路（mock RestTemplate，零网络）。
 * 成功判定 = HTTP 2xx 且业务码 errcode/code=0；业务码非 0 / 非 2xx / 4xx / 连接异常
 * 分别落到不同摘要的 10604；EMAIL 与未知类型直接抛；keyword 前缀文案（钉钉/企微机器人
 * 自定义关键词豁免死锁）；验证文案 = keyword + 固定前缀。
 */
// VECTOR: TAG=step26-unit
class ChannelVerifierTest {

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final ChannelVerifier verifier = new ChannelVerifier(restTemplate, new ObjectMapper());

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

    // ---- 成功路径 ----

    @Test
    void dingtalk_verify_success_with_keyword_prefix_and_millisecond_sign() {
        String target = "https://oapi.dingtalk.com/robot/send?access_token=t1";
        when(restTemplate.postForEntity(anyString(), any(Object.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"errcode\":0}"));
        NfyaChannel ch = channel("DINGTALK", target, "SECret", "OPS");
        long before = System.currentTimeMillis();

        assertThatNoExceptionThrown(ch);
        long after = System.currentTimeMillis();

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> entity = ArgumentCaptor.forClass(Object.class);
        verify(restTemplate).postForEntity(url.capture(), entity.capture(), eq(String.class));
        assertThat(url.getValue()).startsWith(target + "&timestamp=");
        long ts = Long.parseLong(url.getValue().replaceAll(".*timestamp=(\\d+)&sign=.*", "$1"));
        assertThat(ts).isBetween(before, after); // 钉钉毫秒口径
        assertThat(url.getValue()).contains("&sign=" + WebhookSigner.dingTalkSign("SECret", ts));

        Map<String, Object> body = bodyOf(entity);
        // keyword 前缀文案：钉钉机器人开「自定义关键词」时不含关键词必被拒
        assertThat(body.get("text")).isEqualTo(Map.of("content", "OPS【notification4j】渠道验证消息"));
        HttpEntity<?> httpEntity = (HttpEntity<?>) entity.getValue();
        assertThat(httpEntity.getHeaders().getContentType())
                .isEqualTo(org.springframework.http.MediaType.APPLICATION_JSON);
    }

    @Test
    void wecom_verify_posts_target_verbatim_without_sign() {
        when(restTemplate.postForEntity(anyString(), any(Object.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"errcode\":0}"));
        NfyaChannel ch = channel("WECOM", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=k", "ignored", null);

        assertThatNoExceptionThrown(ch);

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> entity = ArgumentCaptor.forClass(Object.class);
        verify(restTemplate).postForEntity(url.capture(), entity.capture(), eq(String.class));
        assertThat(url.getValue()).doesNotContain("sign=").doesNotContain("timestamp=");
        assertThat(bodyOf(entity).get("text"))
                .isEqualTo(Map.of("content", "【notification4j】渠道验证消息")); // keyword null → 纯固定文案
    }

    @Test
    void feishu_verify_puts_second_level_sign_in_body_and_accepts_code_zero() {
        when(restTemplate.postForEntity(anyString(), any(Object.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"code\":0}"));
        NfyaChannel ch = channel("FEISHU", "https://open.feishu.cn/hook/tok", "SECret", "OPS");
        long before = System.currentTimeMillis();

        assertThatNoExceptionThrown(ch);

        ArgumentCaptor<Object> entity = ArgumentCaptor.forClass(Object.class);
        verify(restTemplate).postForEntity(anyString(), entity.capture(), eq(String.class));
        Map<String, Object> body = bodyOf(entity);
        long ts = Long.parseLong((String) body.get("timestamp"));
        assertThat(ts).isBetween(before / 1000, System.currentTimeMillis() / 1000 + 1); // 飞书秒级
        assertThat(body.get("sign")).isEqualTo(WebhookSigner.feishuSign("SECret", ts));
        assertThat(body.get("content")).isEqualTo(Map.of("text", "OPS【notification4j】渠道验证消息"));
    }

    // ---- 失败路径：业务码 / HTTP / 连接 ----

    @Test
    void business_code_nonzero_fails_even_with_http_200() {
        when(restTemplate.postForEntity(anyString(), any(Object.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"errcode\":310000,\"errmsg\":\"keywords not in content\"}"));
        NfyaChannel ch = channel("DINGTALK", "https://oapi.dingtalk.com/x", null, null);

        assertThatThrownBy(() -> verifier.verify(ch))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10604);
                    assertThat(e.getMessage()).contains("keywords not in content"); // 摘要携带响应原文
                });
    }

    @Test
    void feishu_business_code_field_is_code_not_errcode() {
        when(restTemplate.postForEntity(anyString(), any(Object.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"code\":99991663,\"msg\":\"app ticket invalid\"}"));
        NfyaChannel ch = channel("FEISHU", "https://open.feishu.cn/hook/tok", null, null);

        assertThatThrownBy(() -> verifier.verify(ch))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10604);
                    assertThat(e.getMessage()).contains("99991663");
                });
    }

    @Test
    void http_4xx_maps_to_status_only_summary_without_target_leak() {
        when(restTemplate.postForEntity(anyString(), any(Object.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "403",
                        new HttpHeaders(), "https://oapi.dingtalk.com/robot/send?access_token=leak".getBytes(), null));
        NfyaChannel ch = channel("DINGTALK", "https://oapi.dingtalk.com/robot/send?access_token=secret", null, null);

        assertThatThrownBy(() -> verifier.verify(ch))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10604);
                    assertThat(e.getMessage()).isEqualTo("渠道验证失败:HTTP 403"); // 只回状态码，防 e.getMessage 泄漏 target
                });
    }

    @Test
    void connection_exception_maps_to_fixed_summary_without_target_leak() {
        when(restTemplate.postForEntity(anyString(), any(Object.class), eq(String.class)))
                .thenThrow(new RuntimeException("connect timed out"));
        NfyaChannel ch = channel("DINGTALK", "https://oapi.dingtalk.com/robot/send?access_token=secret", null, null);

        assertThatThrownBy(() -> verifier.verify(ch))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10604);
                    assertThat(e.getMessage()).isEqualTo("渠道验证失败:渠道无响应或连接失败"); // 固定摘要防内网信息外泄
                    assertThat(e.getMessage()).doesNotContain("oapi.dingtalk.com");
                });
    }

    @Test
    void non_2xx_response_entity_counts_as_failure() {
        when(restTemplate.postForEntity(anyString(), any(Object.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("{\"errcode\":0}"));
        NfyaChannel ch = channel("DINGTALK", "https://oapi.dingtalk.com/x", null, null);

        assertThatThrownBy(() -> verifier.verify(ch))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10604);
                    assertThat(e.getMessage()).isEqualTo("渠道验证失败:HTTP 502"); // 2xx 判定先行于业务码
                });
    }

    @Test
    void blank_or_null_body_counts_as_failure_with_placeholder() {
        when(restTemplate.postForEntity(anyString(), any(Object.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok((String) null));
        NfyaChannel ch = channel("DINGTALK", "https://oapi.dingtalk.com/x", null, null);

        assertThatThrownBy(() -> verifier.verify(ch))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10604);
                    assertThat(e.getMessage()).isEqualTo("渠道验证失败:无响应摘要");
                });
    }

    @Test
    void non_json_body_counts_as_failure_and_overlong_is_truncated() {
        String longBody = "x".repeat(200);
        when(restTemplate.postForEntity(anyString(), any(Object.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(longBody));
        NfyaChannel ch = channel("DINGTALK", "https://oapi.dingtalk.com/x", null, null);

        assertThatThrownBy(() -> verifier.verify(ch))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10604);
                    assertThat(e.getMessage()).isEqualTo("渠道验证失败:" + "x".repeat(120)); // 摘要截断 ≤120
                });
    }

    // ---- 不支持类型 ----

    @Test
    void email_channel_reports_not_verifiable_via_10604_with_dedicated_message() {
        NfyaChannel ch = channel("EMAIL", "ops@example.com", null, null);

        assertThatThrownBy(() -> verifier.verify(ch))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10604);
                    assertThat(e.getMessage()).contains("EMAIL 通道暂不支持主动验证");
                });
    }

    @Test
    void unknown_channel_type_is_param_error_10100() {
        NfyaChannel ch = channel("SMS", "https://x/y", null, null);

        assertThatThrownBy(() -> verifier.verify(ch))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10100);
                    assertThat(e.getMessage()).contains("channel_type 枚举非法");
                });
    }

    private void assertThatNoExceptionThrown(NfyaChannel ch) {
        verifier.verify(ch); // 成功不抛异常（校验放调用处断言外呼参数）
    }
}
