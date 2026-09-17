package fun.commons.notification4j.service;

import fun.commons.framework4j.web.ApiException;

import java.net.InetAddress;
import java.net.URI;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * API-CHN-002 target 校验（SSRF 防线，§5.7）。
 * IM 类：必须 https + 官方域名白名单（oapi.dingtalk.com / qyapi.weixin.qq.com / open.feishu.cn / *.feishu.cn）
 * + 仅 443 端口 + DNS 解析结果禁内网段（环回/站点本地/链路本地/任意地址/组播）→ 违规 10609。
 * EMAIL：RFC 简版格式 + 长度 ≤254 → 违规 10100（零 DB 判定归 101xx，§6.1）。
 */
public class WebhookTargetValidator {

    static final Set<String> IM_TYPES = Set.of("DINGTALK", "WECOM", "FEISHU");
    static final Set<String> CHANNEL_TYPES = Set.of("DINGTALK", "WECOM", "FEISHU", "EMAIL");
    private static final Set<String> EXACT_HOSTS = Set.of("oapi.dingtalk.com", "qyapi.weixin.qq.com", "open.feishu.cn");
    private static final String WILDCARD_SUFFIX = ".feishu.cn";
    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$");
    private static final int EMAIL_MAX = 254;

    private final WebhookTargetResolver resolver;

    public WebhookTargetValidator(WebhookTargetResolver resolver) {
        this.resolver = resolver;
    }

    /** 渠道类型枚举 + target 合规一体校验（违规抛 10609 / 10100） */
    public void validate(String channelType, String target) {
        if (!CHANNEL_TYPES.contains(channelType)) {
            throw new ApiException(10100, "channel_type 枚举非法(允许 DINGTALK/WECOM/FEISHU/EMAIL)");
        }
        if (target == null || target.isBlank()) {
            throw new ApiException(10100, "target 不能为空");
        }
        if ("EMAIL".equals(channelType)) {
            if (target.length() > EMAIL_MAX || !EMAIL.matcher(target).matches()) {
                throw new ApiException(10100, "邮箱地址格式非法");
            }
            return;
        }
        validateWebhook(target);
    }

    private void validateWebhook(String target) {
        URI uri;
        try {
            uri = new URI(target);
        } catch (Exception e) {
            throw new ApiException(10609, "Webhook 地址非法或非官方域名");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getPort() != -1 && uri.getPort() != 443
                || uri.getHost() == null
                || !isWhitelisted(uri.getHost().toLowerCase())) {
            throw new ApiException(10609, "Webhook 地址非法或非官方域名");
        }
        assertNotInternalHost(uri.getHost().toLowerCase());
    }

    private boolean isWhitelisted(String host) {
        return EXACT_HOSTS.contains(host) || host.endsWith(WILDCARD_SUFFIX);
    }

    /** DNS 解析逐 IP 校验禁内网段；解析失败同 10609（白名单域名解析不出视为不可用） */
    private void assertNotInternalHost(String host) {
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (Exception e) {
            throw new ApiException(10609, "Webhook 地址非法或非官方域名");
        }
        for (InetAddress a : addresses) {
            if (isForbidden(a)) {
                throw new ApiException(10609, "Webhook 地址非法或非官方域名");
            }
        }
    }

    /** 禁用地址集：环回/内网/链路本地/任意/组播 + IPv4-mapped IPv6 展开 + CGNAT 100.64/10 + ULA fc00::/7 */
    private boolean isForbidden(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                || a.isAnyLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 16) {
            // ::ffff:0:0/96 IPv4-mapped → 展开按 IPv4 规则再判
            boolean v4mapped = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) {
                    v4mapped = false;
                    break;
                }
            }
            if (v4mapped && b[10] == (byte) 0xff && b[11] == (byte) 0xff) {
                return isForbiddenV4(b[12], b[13]);
            }
            // fc00::/7 unique local
            return (b[0] & 0xFE) == 0xFC;
        }
        return isForbiddenV4(b[0], b[1]);
    }

    private boolean isForbiddenV4(byte b0, byte b1) {
        int o1 = b0 & 0xFF, o2 = b1 & 0xFF;
        // CGNAT 100.64.0.0/10（运营商级 NAT，非公网）
        return o1 == 100 && (o2 & 0xC0) == 64;
    }
}
