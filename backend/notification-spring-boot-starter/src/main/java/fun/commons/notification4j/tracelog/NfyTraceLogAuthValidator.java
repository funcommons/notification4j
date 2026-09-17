package fun.commons.notification4j.tracelog;

import fun.commons.framework4j.accesstoken.config.AccessTokenProperties;
import fun.commons.framework4j.accesstoken.util.TokenUtils;
import fun.commons.framework4j.tracelog.config.TraceLogAuthValidator;
import fun.commons.framework4j.tracelog.query.SwitchRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

/**
 * notification4j 的 TraceLog 控制台鉴权实现：仅平台运营凭据签发的 token 可查询/开关/导出。
 * <p>
 * 背景：tracelog API（/api/logs/**）的 Controller 无 {@code @RequiresToken} 注解，
 * accesstoken 拦截器直接放行（注解驱动），TokenContext 不会填充 —— 故此处经
 * {@link RequestContextHolder} 取当前请求，自行解析 Authorization 中的 JWT。
 * <p>
 * 「平台运营」判定：notification4j 平台凭据（notification4j.security.platform.*）签发的 token
 * 其 tenant_id claim 恒为 0（合成平台租户，见 DefaultNfyAuthService#syntheticPlatformTenant），
 * 以 tenant_id==0 作为运营身份标识；普通租户 token 的 tenant_id 为真实雪花 ID（>0）一律拒绝。
 * <p>
 * 业务方可自行声明 {@code traceLogAuthValidator} Bean 覆盖本实现。
 *
 * @see fun.commons.framework4j.tracelog.config.TraceLogAuthValidator
 */
@Slf4j
public class NfyTraceLogAuthValidator implements TraceLogAuthValidator {

    private final ObjectProvider<AccessTokenProperties> accessTokenPropertiesProvider;

    public NfyTraceLogAuthValidator(ObjectProvider<AccessTokenProperties> accessTokenPropertiesProvider) {
        this.accessTokenPropertiesProvider = accessTokenPropertiesProvider;
    }

    /**
     * 当前请求是否平台运营：有效 JWT + keyHash 匹配平台凭据。
     * <p>
     * notification4j 平台凭据（notification4j.security.platform.*）签发 token 时 claims.tenant_id=0
     * （合成平台租户，见 DefaultNfyAuthService#syntheticPlatformTenant），policy.key=[tenant_id]
     * 使其 keyHash = HMAC-SHA256("0", hashSalt)。JWT 不携带 tenant_id（claims 存 Redis
     * metadata），故比对 hash 即可识别平台 token —— 纯本地计算，无 Redis 往返。
     */
    private boolean isPlatformOperator() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return false;
        }
        String authorization = attrs.getRequest().getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        AccessTokenProperties props = accessTokenPropertiesProvider.getIfAvailable();
        if (props == null) {
            return false;
        }
        try {
            Map<String, Object> payload = TokenUtils.parseToken(authorization.substring(7), props.getSecretKey());
            Object hash = payload.get("hash");
            if (hash == null) {
                return false;
            }
            String platformHash = TokenUtils.calculateKeyHash("0", props.getHashSalt());
            return java.security.MessageDigest.isEqual(
                    hash.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    platformHash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean canQuery(String operatorId, String tenantId) {
        boolean allowed = isPlatformOperator();
        if (!allowed) {
            log.warn("[TraceLog] 拒绝查询: operator={}", operatorId);
        }
        return allowed;
    }

    @Override
    public boolean canOpenSwitch(String operatorId, String tenantId, SwitchRequest req) {
        boolean allowed = isPlatformOperator();
        log.info("[TraceLog] 开关请求: operator={}, type={}, value={}, level={}, allowed={}",
                operatorId, req.getType(), req.getValue(), req.getLevel(), allowed);
        return allowed;
    }

    @Override
    public boolean canExport(String operatorId, String tenantId) {
        boolean allowed = isPlatformOperator();
        if (!allowed) {
            log.warn("[TraceLog] 拒绝导出: operator={}", operatorId);
        }
        return allowed;
    }
}
