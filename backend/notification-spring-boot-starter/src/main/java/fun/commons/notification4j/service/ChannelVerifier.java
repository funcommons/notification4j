package fun.commons.notification4j.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.entity.NfyaChannel;
import fun.commons.notification4j.util.WebhookSigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API-CHN-003 渠道验证（§5.7）。
 * 成功判定 = HTTP 2xx **且渠道业务码成功**（钉钉/企微 errcode=0、飞书 code=0）——
 * 业务失败（关键词不匹配/加签错误/机器人被移出群）时 HTTP 仍 200，只看状态码会误置 ENABLED。
 * 成功不抛异常；失败抛 10604「渠道验证失败:{摘要}」。超时 5s。
 * EMAIL 验证依赖第 6 步 SMTP 适配器，本步先返回 10604（发送时校验兜底）。
 */
@Slf4j
@RequiredArgsConstructor
public class ChannelVerifier {

    private static final String VERIFY_TEXT = "【notification4j】渠道验证消息";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /** 供测试绑定 MockRestServiceServer（避免向外暴露 RestTemplate Bean 污染宿主装配，评审第 4 步 P1） */
    public RestTemplate restTemplate() {
        return restTemplate;
    }

    public void verify(NfyaChannel ch) {
        switch (ch.getChannelType()) {
            case "DINGTALK" -> verifyDingTalk(ch);
            case "WECOM" -> verifyWecom(ch);
            case "FEISHU" -> verifyFeishu(ch);
            case "EMAIL" -> throw new ApiException(10604, "渠道验证失败:EMAIL 通道暂不支持主动验证(首次投递时校验)");
            default -> throw new ApiException(10100, "channel_type 枚举非法");
        }
    }

    /** 渠道验证/投递共用的 5s 超时 RestTemplate 工厂（autoconfig 未提供 RestTemplate 时兜底） */
    public static RestTemplate timeoutRestTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(5000);
        return new RestTemplate(f);
    }

    private void verifyDingTalk(NfyaChannel ch) {
        String secret = ch.getSecret() == null ? "" : ch.getSecret();
        String url = ch.getTarget();
        if (!secret.isBlank()) {
            long ts = System.currentTimeMillis(); // 钉钉要求毫秒
            url += (url.contains("?") ? "&" : "?") + "timestamp=" + ts + "&sign=" + WebhookSigner.dingTalkSign(secret, ts);
        }
        postAndJudge(url, textBody("msgtype", "text", "text", verifyText(ch)), ch);
    }

    private void verifyWecom(NfyaChannel ch) {
        postAndJudge(ch.getTarget(), textBody("msgtype", "text", "text", verifyText(ch)), ch);
    }

    private void verifyFeishu(NfyaChannel ch) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msg_type", "text");
        body.put("content", Map.of("text", verifyText(ch)));
        String secret = ch.getSecret() == null ? "" : ch.getSecret();
        if (!secret.isBlank()) {
            long ts = System.currentTimeMillis() / 1000; // 飞书要求秒
            body.put("timestamp", String.valueOf(ts));
            body.put("sign", WebhookSigner.feishuSign(secret, ts));
        }
        postAndJudge(ch.getTarget(), body, ch);
    }

    /** 验证文案前缀用户关键词：钉钉/企微机器人开「自定义关键词」时不含关键词必被拒（评审第 4 步 P1） */
    private String verifyText(NfyaChannel ch) {
        String keyword = ch.getKeyword() == null ? "" : ch.getKeyword().trim();
        return keyword.isEmpty() ? VERIFY_TEXT : keyword + VERIFY_TEXT;
    }

    private Map<String, Object> textBody(String typeKey, String typeVal, String textKey, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(typeKey, typeVal);
        body.put(textKey, Map.of("content", text));
        return body;
    }

    private void postAndJudge(String url, Object body, NfyaChannel ch) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp;
        try {
            resp = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
        } catch (ApiException e) {
            throw e;
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // 4xx/5xx 有响应：只回状态码（评审第 4 步 P2：e.getMessage 可能携带 target/内网信息）
            throw new ApiException(10604, "渠道验证失败:HTTP " + e.getStatusCode().value());
        } catch (Exception e) {
            // 连接拒绝/超时等：固定摘要 + 原文留日志
            log.warn("[Channel] 验证外呼异常 channel={} target-host={}",
                    ch.getId(), safeHost(ch.getTarget()), e);
            throw new ApiException(10604, "渠道验证失败:渠道无响应或连接失败");
        }
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new ApiException(10604, "渠道验证失败:HTTP " + resp.getStatusCode().value());
        }
        if (!businessCodeOk(resp.getBody())) {
            throw new ApiException(10604, "渠道验证失败:" + summarize(resp.getBody()));
        }
    }

    private String safeHost(String target) {
        try {
            return new java.net.URI(target).getHost();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 业务码成功判定：errcode(钉钉/企微)=0 或 code(飞书)=0；字段缺失/解析失败均判失败 */
    private boolean businessCodeOk(String respBody) {
        try {
            JsonNode node = objectMapper.readTree(respBody == null ? "" : respBody);
            if (node.has("errcode")) {
                return node.get("errcode").asInt(-1) == 0;
            }
            if (node.has("code")) {
                return node.get("code").asInt(-1) == 0;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private String summarize(String s) {
        if (s == null || s.isBlank()) {
            return "无响应摘要";
        }
        return s.length() > 120 ? s.substring(0, 120) : s;
    }
}
