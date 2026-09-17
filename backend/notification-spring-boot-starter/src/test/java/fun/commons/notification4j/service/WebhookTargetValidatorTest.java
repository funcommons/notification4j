package fun.commons.notification4j.service;

import fun.commons.framework4j.web.ApiException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 第 24 步纯函数层单测：WebhookTargetValidator（SSRF 防线）。
 * DNS 解析口 {@link WebhookTargetResolver} 为函数接口 → 以进程内确定性桩替代（零网络）：
 * 白名单域名预置 InetAddress.getByAddress/getByName（IP 字面量，不触解析器）。
 * 离线可判定分支全覆盖：协议/端口/白名单精确与通配/格式异常/解析失败/内网各段/CGNAT 边界/
 * IPv4-mapped 展开/ULA fc00::/7 边界/多 IP 逐个校验/空结果。
 * 真实公网域名解析分支（InetAddress::getAllByName 默认实现）依赖网络 → 不在本步范围。
 */
// VECTOR: TAG=step24-unit
class WebhookTargetValidatorTest {

    private final Map<String, InetAddress[]> dns = new HashMap<>();

    private final WebhookTargetValidator validator = new WebhookTargetValidator(host -> {
        InetAddress[] addresses = dns.get(host);
        if (addresses == null) {
            throw new UnknownHostException(host);
        }
        return addresses;
    });

    private void dns(String host, String... ipLiterals) throws UnknownHostException {
        InetAddress[] addresses = new InetAddress[ipLiterals.length];
        for (int i = 0; i < ipLiterals.length; i++) {
            addresses[i] = InetAddress.getByName(ipLiterals[i]); // 字面量不触发 DNS
        }
        dns.put(host, addresses);
    }

    private ApiException code10609(Runnable r) {
        try {
            r.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("预期 10609 未抛出");
    }

    private void expect10609(String channelType, String target) {
        assertThat(code10609(() -> validator.validate(channelType, target)).getCode()).isEqualTo(10609);
    }

    // ---- 渠道类型/target 基础校验 ----

    @Test
    void unknown_channel_type_throws_10100() {
        assertThat(code10609(() -> validator.validate("SMS", "https://oapi.dingtalk.com/x")).getCode())
                .isEqualTo(10100);
    }

    @Test
    void null_or_blank_target_throws_10100() {
        assertThat(code10609(() -> validator.validate("DINGTALK", null)).getCode()).isEqualTo(10100);
        assertThat(code10609(() -> validator.validate("DINGTALK", "   ")).getCode()).isEqualTo(10100);
    }

    // ---- EMAIL：RFC 简版 + 长度 ----

    @Test
    void email_valid_passes_without_dns() {
        assertThatCode(() -> validator.validate("EMAIL", "a.b+c@sub.example.com")).doesNotThrowAnyException();
    }

    @Test
    void email_over_254_chars_throws_10100() {
        String local = "a".repeat(250);
        assertThat(code10609(() -> validator.validate("EMAIL", local + "@example.com")).getCode()).isEqualTo(10100);
    }

    @Test
    void email_malformed_throws_10100() {
        assertThat(code10609(() -> validator.validate("EMAIL", "plainaddress")).getCode()).isEqualTo(10100);
        assertThat(code10609(() -> validator.validate("EMAIL", "a@nodot")).getCode()).isEqualTo(10100);
        assertThat(code10609(() -> validator.validate("EMAIL", "a b@example.com")).getCode()).isEqualTo(10100);
        assertThat(code10609(() -> validator.validate("EMAIL", "@example.com")).getCode()).isEqualTo(10100);
    }

    // ---- IM：协议/端口/host/白名单（均在 DNS 前）----

    @Test
    void non_https_throws_10609() {
        expect10609("DINGTALK", "http://oapi.dingtalk.com/robot/send?access_token=x");
    }

    @Test
    void explicit_non_443_port_throws_10609() {
        expect10609("DINGTALK", "https://oapi.dingtalk.com:8443/robot/send");
    }

    @Test
    void explicit_443_port_is_allowed() throws UnknownHostException {
        dns("oapi.dingtalk.com", "203.0.113.9");
        assertThatCode(() -> validator.validate("DINGTALK", "https://oapi.dingtalk.com:443/robot/send"))
                .doesNotThrowAnyException();
    }

    @Test
    void missing_host_throws_10609() {
        expect10609("DINGTALK", "https:///robot/send");
    }

    @Test
    void non_whitelisted_host_throws_10609() {
        expect10609("WECOM", "https://evil.example.com/webhook");
    }

    @Test
    void bare_feishu_domain_without_subdomain_is_not_whitelisted() {
        // 白名单 = 精确三域 或 *.feishu.cn（须有点前缀）；feishu.cn 本域不在列
        expect10609("FEISHU", "https://feishu.cn/hook");
    }

    @Test
    void feishu_suffix_must_be_proper_subdomain() {
        expect10609("FEISHU", "https://evilfeishu.cn.evil.com/hook");
    }

    @Test
    void wildcard_feishu_subdomain_passes_whitelist() throws UnknownHostException {
        dns("abc.feishu.cn", "203.0.113.10");
        assertThatCode(() -> validator.validate("FEISHU", "https://abc.feishu.cn/open-apis/bot/v2/hook/tok"))
                .doesNotThrowAnyException();
    }

    @Test
    void scheme_and_host_case_insensitive() throws UnknownHostException {
        dns("open.feishu.cn", "203.0.113.11");
        assertThatCode(() -> validator.validate("FEISHU", "HTTPS://OPEN.FEISHU.CN/hook"))
                .doesNotThrowAnyException();
    }

    @Test
    void unparseable_uri_throws_10609() {
        expect10609("DINGTALK", "https://oapi.dingtalk.com/robot/send?access_token=a b"); // 空格非法 URI
    }

    @Test
    void dns_resolution_failure_for_whitelisted_host_throws_10609() {
        // 白名单域名解析不出 = 不可用（未预置 → 桩抛 UnknownHostException）
        expect10609("DINGTALK", "https://oapi.dingtalk.com/robot/send?access_token=x");
    }

    // ---- 禁用地址段（经 resolver 桩注入）----

    @Test
    void forbidden_v4_segments_throw_10609() throws UnknownHostException {
        String[] forbidden = {
                "127.0.0.1",      // 环回
                "10.1.2.3",       // 站点本地
                "172.16.0.9",     // 站点本地
                "192.168.1.5",    // 站点本地
                "169.254.3.4",    // 链路本地
                "0.0.0.0",        // 任意地址
                "224.0.0.1",      // 组播
                "100.64.0.1",     // CGNAT 下界
                "100.127.255.254" // CGNAT 上界
        };
        for (String ip : forbidden) {
            dns("oapi.dingtalk.com", ip);
            expect10609("DINGTALK", "https://oapi.dingtalk.com/robot/send?access_token=x");
        }
    }

    @Test
    void cgnat_boundaries_outside_range_are_allowed() throws UnknownHostException {
        dns("oapi.dingtalk.com", "100.63.255.255");
        assertThatCode(() -> validator.validate("DINGTALK", "https://oapi.dingtalk.com/x")).doesNotThrowAnyException();
        dns("oapi.dingtalk.com", "100.128.0.1");
        assertThatCode(() -> validator.validate("DINGTALK", "https://oapi.dingtalk.com/x")).doesNotThrowAnyException();
    }

    @Test
    void forbidden_ipv6_segments_throw_10609() throws UnknownHostException {
        String[] forbidden = {
                "::1",               // 环回
                "::ffff:10.0.0.5",   // IPv4-mapped → 内网展开判定
                "::ffff:127.0.0.1",  // IPv4-mapped → 环回展���判定
                "fc00::1",           // ULA fc00::/7
                "fd12::1"            // ULA fd00::/8
        };
        for (String ip : forbidden) {
            dns("oapi.dingtalk.com", ip);
            expect10609("DINGTALK", "https://oapi.dingtalk.com/robot/send?access_token=x");
        }
    }

    @Test
    void public_ipv6_and_non_ula_prefixes_are_allowed() throws UnknownHostException {
        dns("oapi.dingtalk.com", "fe00::1"); // 0xfe&0xFE != 0xFC → 非 ULA（非 link-local 前缀）
        assertThatCode(() -> validator.validate("DINGTALK", "https://oapi.dingtalk.com/x")).doesNotThrowAnyException();
        dns("oapi.dingtalk.com", "::ffff:203.0.113.9"); // v4-mapped 公网展开 → 放行
        assertThatCode(() -> validator.validate("DINGTALK", "https://oapi.dingtalk.com/x")).doesNotThrowAnyException();
    }

    @Test
    void any_forbidden_ip_among_multiple_addresses_throws_10609() throws UnknownHostException {
        dns("oapi.dingtalk.com", "203.0.113.9", "10.0.0.9");
        expect10609("DINGTALK", "https://oapi.dingtalk.com/robot/send?access_token=x");
    }

    @Test
    void empty_resolution_result_passes() throws UnknownHostException {
        dns("oapi.dingtalk.com");
        assertThatCode(() -> validator.validate("DINGTALK", "https://oapi.dingtalk.com/x")).doesNotThrowAnyException();
    }
}
