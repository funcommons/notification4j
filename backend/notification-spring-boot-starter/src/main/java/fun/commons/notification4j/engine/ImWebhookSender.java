package fun.commons.notification4j.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.notification4j.entity.NfyaChannel;
import fun.commons.notification4j.entity.NfyaDelivery;
import fun.commons.notification4j.util.WebhookSigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IM 群机器人投递（钉钉/企微/飞书）：加签口径与验证一致（钉钉毫秒 query 携带、
 * 飞书秒级 body 携带、企微 key 即鉴权）；成功判定 = 业务码（errcode/code）=0。
 * 文案携带渠道 keyword 前缀（关键词机器人安全设置）。
 */
@Slf4j
@RequiredArgsConstructor
public class ImWebhookSender implements ChannelSender {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public String channelType() {
        return "IM"; // 标识用：实际匹配走 supports（一实例服务三种 IM 机器人）
    }

    @Override
    public boolean supports(String channelType) {
        return "DINGTALK".equals(channelType) || "WECOM".equals(channelType) || "FEISHU".equals(channelType);
    }

    @Override
    public SendResult send(NfyaDelivery d, NfyaChannel ch) {
        return switch (ch.getChannelType()) {
            case "DINGTALK" -> post(dingTalkUrl(ch), textBody("msgtype", "text", "text", content(d, ch)), ch);
            case "WECOM" -> post(ch.getTarget(), textBody("msgtype", "text", "text", content(d, ch)), ch);
            case "FEISHU" -> post(ch.getTarget(), feishuBody(d, ch), ch);
            default -> SendResult.fail("不支持的渠道类型: " + ch.getChannelType());
        };
    }

    /** 投递正文 = title 快照；关键词机器人前置 keyword（安全设置包含匹配） */
    private String content(NfyaDelivery d, NfyaChannel ch) {
        String keyword = ch.getKeyword() == null ? "" : ch.getKeyword().trim();
        String title = d.getTitle() == null || d.getTitle().isBlank() ? "notification4j 通知" : d.getTitle();
        return keyword.isEmpty() ? title : keyword + " " + title;
    }

    private String dingTalkUrl(NfyaChannel ch) {
        String secret = ch.getSecret() == null ? "" : ch.getSecret();
        String url = ch.getTarget();
        if (!secret.isBlank()) {
            long ts = System.currentTimeMillis();
            url += (url.contains("?") ? "&" : "?") + "timestamp=" + ts + "&sign=" + WebhookSigner.dingTalkSign(secret, ts);
        }
        return url;
    }

    private Map<String, Object> textBody(String typeKey, String typeVal, String textKey, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(typeKey, typeVal);
        body.put(textKey, Map.of("content", text));
        return body;
    }

    private Map<String, Object> feishuBody(NfyaDelivery d, NfyaChannel ch) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msg_type", "text");
        body.put("content", Map.of("text", content(d, ch)));
        String secret = ch.getSecret() == null ? "" : ch.getSecret();
        if (!secret.isBlank()) {
            long ts = System.currentTimeMillis() / 1000;
            body.put("timestamp", String.valueOf(ts));
            body.put("sign", WebhookSigner.feishuSign(secret, ts));
        }
        return body;
    }

    private SendResult post(String url, Object body, NfyaChannel ch) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp;
        try {
            resp = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            return SendResult.fail("HTTP " + e.getStatusCode().value());
        } catch (Exception e) {
            // 连接类故障留痕（评审 P2：不落 target 防凭据泄漏，仅渠道 id + 异常类名）
            log.warn("[Engine] IM 投递外呼异常 channel={} type={} err={}",
                    ch.getId(), ch.getChannelType(), e.getClass().getSimpleName());
            return SendResult.fail("渠道无响应或连接失败");
        }
        if (!resp.getStatusCode().is2xxSuccessful()) {
            return SendResult.fail("HTTP " + resp.getStatusCode().value());
        }
        return businessOk(resp.getBody())
                ? SendResult.success("ok")
                : SendResult.fail(summarize(resp.getBody()));
    }

    private boolean businessOk(String body) {
        try {
            JsonNode node = objectMapper.readTree(body == null ? "" : body);
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
