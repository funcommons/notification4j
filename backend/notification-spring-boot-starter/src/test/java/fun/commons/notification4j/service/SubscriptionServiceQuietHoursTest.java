package fun.commons.notification4j.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.dto.PutSubscriptionsRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 第 24 步纯函数层单测：SubscriptionService#normalizeQuietHours（免打扰保存侧归一化）。
 * private → 同包反射调用（mapper 等依赖传 null，方法为纯逻辑不触依赖）。
 * 分支：null/双缺省 → "{}"；只给其一 → 10100 成对；格式非 HH:mm → 10100；start==end → 10100；
 * 合法（含跨午夜、首尾空白 trim）→ 紧凑 json（start 在前）。
 */
// VECTOR: TAG=step24-unit
class SubscriptionServiceQuietHoursTest {

    private static final Method NORMALIZE = normalize();

    private static Method normalize() {
        try {
            Method m = SubscriptionService.class.getDeclaredMethod("normalizeQuietHours",
                    PutSubscriptionsRequest.QuietHours.class);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String invoke(PutSubscriptionsRequest.QuietHours qh) {
        try {
            SubscriptionService service = new SubscriptionService(null, null, null, new ObjectMapper());
            return (String) NORMALIZE.invoke(service, qh);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof ApiException ae) {
                throw ae;
            }
            throw new IllegalStateException(e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException codeOf(Runnable r) {
        try {
            r.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("预期 ApiException 未抛出");
    }

    // ---- 缺省 → 未启用 ----

    @Test
    void null_quiet_hours_normalizes_to_empty_object() {
        assertThat(invoke(null)).isEqualTo("{}");
    }

    @Test
    void both_blank_normalizes_to_empty_object() {
        assertThat(invoke(new PutSubscriptionsRequest.QuietHours(null, null))).isEqualTo("{}");
        assertThat(invoke(new PutSubscriptionsRequest.QuietHours("  ", ""))).isEqualTo("{}");
    }

    // ---- 成对校验 ----

    @Test
    void start_only_throws_10100() {
        ApiException e = codeOf(() -> invoke(new PutSubscriptionsRequest.QuietHours("22:00", null)));
        assertThat(e.getCode()).isEqualTo(10100);
        assertThat(e.getMessage()).contains("成对");
    }

    @Test
    void end_only_throws_10100() {
        assertThat(codeOf(() -> invoke(new PutSubscriptionsRequest.QuietHours(null, "08:00"))).getCode())
                .isEqualTo(10100);
    }

    // ---- HH:mm 格式 ----

    @Test
    void invalid_format_throws_10100() {
        assertThat(codeOf(() -> invoke(new PutSubscriptionsRequest.QuietHours("9:00", "08:00"))).getCode())
                .isEqualTo(10100);
        assertThat(codeOf(() -> invoke(new PutSubscriptionsRequest.QuietHours("22:00", "08:60"))).getCode())
                .isEqualTo(10100);
        assertThat(codeOf(() -> invoke(new PutSubscriptionsRequest.QuietHours("2200", "08:00"))).getCode())
                .isEqualTo(10100);
        assertThat(codeOf(() -> invoke(new PutSubscriptionsRequest.QuietHours("24:00", "08:00"))).getCode())
                .isEqualTo(10100);
    }

    // ---- start==end 空窗 ----

    @Test
    void start_equals_end_throws_10100() {
        ApiException e = codeOf(() -> invoke(new PutSubscriptionsRequest.QuietHours("08:00", "08:00")));
        assertThat(e.getCode()).isEqualTo(10100);
        assertThat(e.getMessage()).contains("不能相同");
    }

    // ---- 合法：紧凑 json，start 在前 ----

    @Test
    void valid_pair_normalizes_to_ordered_json() {
        assertThat(invoke(new PutSubscriptionsRequest.QuietHours("22:00", "08:00")))
                .isEqualTo("{\"start\":\"22:00\",\"end\":\"08:00\"}");
    }

    @Test
    void valid_pair_boundaries_and_trim() {
        assertThat(invoke(new PutSubscriptionsRequest.QuietHours(" 23:59 ", "00:00")))
                .isEqualTo("{\"start\":\"23:59\",\"end\":\"00:00\"}");
        assertThat(invoke(new PutSubscriptionsRequest.QuietHours("00:00", "23:59")))
                .isEqualTo("{\"start\":\"00:00\",\"end\":\"23:59\"}");
    }

    @Test
    void invalid_throws_via_helper_assertion() {
        // 防御性自检：helper 在无异常时必须显式失败（覆盖 codeOf 的兜底分支语义）
        assertThatThrownBy(() -> codeOf(() -> invoke(new PutSubscriptionsRequest.QuietHours("22:00", "08:00"))))
                .isInstanceOf(AssertionError.class);
    }
}
