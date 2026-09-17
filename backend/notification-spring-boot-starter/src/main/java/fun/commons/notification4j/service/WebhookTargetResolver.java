package fun.commons.notification4j.service;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * SSRF 防线的 DNS 解析口（函数接口）。
 * 默认实现 = InetAddress::getAllByName；测试/离线环境以同名 Bean 覆盖为确定性实现。
 */
@FunctionalInterface
public interface WebhookTargetResolver {

    InetAddress[] resolve(String host) throws UnknownHostException;
}
