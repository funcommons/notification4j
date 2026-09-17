package fun.commons.notification4j.remote;

/**
 * S2S JWT 供源（编码第 30 步提炼时的解耦面）：把 framework4j-accesstoken 的
 * {@code AccessTokenGenerator#generateToken("SERVICE", Map.of("tenant_id", tenantId))}
 * 适配成本函数接口，使 {@link AuthenticatedHttpTransport} 不持有 accesstoken 类的硬引用——
 * 业务方未引 framework4j-accesstoken（optional）时类路径无该类也不崩（装配层
 * {@code @ConditionalOnClass/@ConditionalOnMissingClass} 双分支降级为仅 HMAC 签名）。
 */
@FunctionalInterface
public interface ServiceTokenSupplier {

    /**
     * 生成 S2S JWT。
     *
     * @param tenantId 业务方租户 OpenID（写入 token claims）
     * @return JWT 原文（transport 会加 {@code Bearer } 前缀）
     * @throws Exception 生成失败（transport 捕获后 warn 降级为仅 HMAC 签名，与全量 starter 口径一致）
     */
    String generate(String tenantId);
}
