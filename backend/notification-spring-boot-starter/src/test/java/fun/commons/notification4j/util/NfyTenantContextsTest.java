package fun.commons.notification4j.util;

import fun.commons.framework4j.accesstoken.context.TokenContext;
import fun.commons.framework4j.tenant.context.UserIdContext;
import fun.commons.framework4j.web.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 第 24 步纯函数层单测：租户上下文取值（Object 中转防 CCE 的三种 claim 型别 + userId 守卫）。
 * 不起 Spring：TokenContext 走公开 set，UserIdContext.set 为框架包私有 → 反射操作其 HOLDER ThreadLocal
 * （主代码零改动，测试后清理防污染同 JVM 其他用例）。
 */
// VECTOR: TAG=step24-unit
class NfyTenantContextsTest {

    @AfterEach
    void cleanup() {
        TokenContext.clear();
        setUserId(null);
    }

    // ---- tenantId()：claim 型别 Object 中转（Integer/Long/String 均不得 CCE）----

    @Test
    void tenantId_accepts_integer_claim() {
        TokenContext.set("TENANT", Map.of("tenant_id", 42));
        assertThat(NfyTenantContexts.tenantId()).isEqualTo(42L);
    }

    @Test
    void tenantId_accepts_long_claim() {
        TokenContext.set("TENANT", Map.of("tenant_id", 7L));
        assertThat(NfyTenantContexts.tenantId()).isEqualTo(7L);
    }

    @Test
    void tenantId_accepts_string_claim() {
        TokenContext.set("TENANT", Map.of("tenant_id", "123"));
        assertThat(NfyTenantContexts.tenantId()).isEqualTo(123L);
    }

    @Test
    void tenantId_missing_claim_fails_fast() {
        // 缺失即鉴权层缺陷：String.valueOf(null)="null" → NumberFormatException fail-fast
        TokenContext.set("TENANT", new HashMap<>());
        assertThatThrownBy(NfyTenantContexts::tenantId).isInstanceOf(NumberFormatException.class);
    }

    // ---- userId()：X-User-Id 缺失守卫 10101 ----

    @Test
    void userId_returns_current_value() {
        setUserId("u-001");
        assertThat(NfyTenantContexts.userId()).isEqualTo("u-001");
    }

    @Test
    void userId_missing_throws_10101() {
        setUserId(null);
        assertThatThrownBy(NfyTenantContexts::userId)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10101));
    }

    @Test
    void userId_blank_throws_10101() {
        setUserId("   ");
        assertThatThrownBy(NfyTenantContexts::userId)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10101));
    }

    /** UserIdContext.set 为框架包私有：反射置 HOLDER ThreadLocal（同 JVM 线程内，用后清理） */
    private static void setUserId(String userid) {
        try {
            Field holder = UserIdContext.class.getDeclaredField("HOLDER");
            holder.setAccessible(true);
            @SuppressWarnings("unchecked")
            ThreadLocal<String> tl = (ThreadLocal<String>) holder.get(null);
            if (userid == null) {
                tl.remove();
            } else {
                tl.set(userid);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("UserIdContext.HOLDER 反射失败", e);
        }
    }
}
