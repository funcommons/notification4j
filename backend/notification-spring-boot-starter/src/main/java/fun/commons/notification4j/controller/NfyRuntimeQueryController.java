package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.TenantDomain;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.notification4j.service.MessageService;
import fun.commons.notification4j.util.NfyTenantContexts;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * API-MSG-003 按 biz_no 查发送结果 + API-DICT-001 字典下发（runtime 面）。
 * 注册路径双通道：异基包走 autoconfig（enable-api 开关），同基包走组件扫描（沿 benefit4j 惯例）。
 */
@TenantDomain
@RequiresToken(value = "TENANT", type = "access")
@RestController
@RequestMapping("/nfy/api/v1/runtime")
@RequiredArgsConstructor
public class NfyRuntimeQueryController {

    private final MessageService messageService;

    /** MSG-003 按业务号查发送结果（T 鉴权无 U；10400 防探测） */
    @GetMapping("/send-results/{biz_no}")
    public ApiResponse<Map<String, Object>> sendResults(@PathVariable("biz_no") String bizNo) {
        return ApiResponse.success(messageService.sendResults(NfyTenantContexts.tenantId(), bizNo));
    }

    /** DICT-001 字典下发（枚举全量+文案，前端禁硬编码 §5.9.1） */
    @GetMapping("/dictionaries")
    public ApiResponse<Map<String, Object>> dictionaries() {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("levels", List.of(
                Map.of("value", "NORMAL", "label", "普通"),
                Map.of("value", "IMPORTANT", "label", "重要"),
                Map.of("value", "URGENT", "label", "紧急")));
        data.put("channel_types", List.of(
                Map.of("value", "INAPP", "label", "站内信"),
                Map.of("value", "DINGTALK", "label", "钉钉"),
                Map.of("value", "WECOM", "label", "企业微信"),
                Map.of("value", "FEISHU", "label", "飞书"),
                Map.of("value", "EMAIL", "label", "邮件")));
        data.put("read_status", List.of(
                Map.of("value", "UNREAD", "label", "未读"),
                Map.of("value", "READ", "label", "已读")));
        data.put("delivery_status", List.of(
                Map.of("value", "PENDING", "label", "待投递"),
                Map.of("value", "SENDING", "label", "投递中"),
                Map.of("value", "SUCCESS", "label", "成功"),
                Map.of("value", "FAILED", "label", "失败"),
                Map.of("value", "DEAD", "label", "死信")));
        return ApiResponse.success(data);
    }
}
