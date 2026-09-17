package fun.commons.notification4j.util;

import fun.commons.framework4j.accesstoken.context.TokenContext;
import fun.commons.framework4j.tenant.context.UserIdContext;
import fun.commons.framework4j.web.ApiException;

/**
 * 租户上下文取值工具（评审第 3 轮 P2：消除各控制器对 tenantId() 的复制）。
 * 先赋值 Object 再 valueOf：链式 String.valueOf(claim) 会命中 valueOf(char[]) 重载，
 * 编译器对泛型返回值插入 [C 强转（claim 实际为 Integer → CCE → 10001）。
 */
public final class NfyTenantContexts {

    private NfyTenantContexts() {
    }

    /** TENANT token 的 tenant_id claim（policy 绑定单值 key=tenant_id，缺失即鉴权层缺陷，fail-fast 抛出） */
    public static long tenantId() {
        Object v = TokenContext.getClaim("tenant_id");
        return Long.parseLong(String.valueOf(v));
    }

    /** T+U 端点的用户头守卫：X-User-Id 缺失时 register 等会插 userid=null → 误导性 DB 异常（评审第 4 步 P2） */
    public static String userId() {
        String userid = UserIdContext.currentUserId();
        if (userid == null || userid.isBlank()) {
            throw new ApiException(10101, "X-User-Id 不能为空");
        }
        return userid;
    }
}
