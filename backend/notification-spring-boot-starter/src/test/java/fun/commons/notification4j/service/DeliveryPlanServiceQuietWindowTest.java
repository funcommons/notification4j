package fun.commons.notification4j.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 24 步纯函数层单测：DeliveryPlanService#quietWindowEnd（免打扰静默窗判定）。
 * private → 同包反射调用（不为可测性改业务代码）；now 以固定时刻构造且基于系统默认时区，
 * atZoneSameInstant(systemDefault) 为恒等变换 → 断言与宿主机时区/时钟无关。
 * 分支：null/blank/{}/脏 jsonb fail-open/非字符串字段/非法 HH:mm/start==end/同日窗两界/跨午夜窗前段与窗后段/窗外。
 */
// VECTOR: TAG=step24-unit
class DeliveryPlanServiceQuietWindowTest {

    private static final Method QUIET_WINDOW_END = quietWindowEnd();

    private static Method quietWindowEnd() {
        try {
            Method m = DeliveryPlanService.class.getDeclaredMethod("quietWindowEnd", String.class, OffsetDateTime.class);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static OffsetDateTime invoke(String quietHours, OffsetDateTime now) {
        try {
            DeliveryPlanService service = new DeliveryPlanService(null, null, null, null, new ObjectMapper());
            return (OffsetDateTime) QUIET_WINDOW_END.invoke(service, quietHours, now);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 固定时刻（2026-06-15，无 DST 风险日）：以系统默认时区构造 → 判定内 atZoneSameInstant 为恒等 */
    private static OffsetDateTime at(int hour, int minute) {
        return LocalDateTime.of(2026, 6, 15, hour, minute, 0).atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    /** 断言回系统的本地挂钟时间（与宿主时区无关） */
    private static LocalDateTime wall(OffsetDateTime t) {
        return t.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    // ---- 缺省/脏数据 fail-open ----

    @Test
    void null_quiet_hours_is_not_muted() {
        assertThat(invoke(null, at(23, 0))).isNull();
    }

    @Test
    void blank_quiet_hours_is_not_muted() {
        assertThat(invoke("   ", at(23, 0))).isNull();
    }

    @Test
    void empty_object_is_not_muted() {
        assertThat(invoke("{}", at(23, 0))).isNull();
    }

    @Test
    void empty_object_with_spaces_is_not_muted() {
        assertThat(invoke(" {} ", at(23, 0))).isNull();
    }

    @Test
    void dirty_jsonb_fails_open() {
        assertThat(invoke("not-a-json{", at(23, 0))).isNull();
    }

    @Test
    void non_string_fields_fail_open() {
        assertThat(invoke("{\"start\":22,\"end\":8}", at(23, 0))).isNull();
        assertThat(invoke("{\"start\":\"22:00\"}", at(23, 0))).isNull();
        assertThat(invoke("{\"start\":\"\",\"end\":\"08:00\"}", at(23, 0))).isNull();
    }

    @Test
    void unparseable_time_fails_open() {
        assertThat(invoke("{\"start\":\"24:00\",\"end\":\"08:00\"}", at(23, 0))).isNull();
        assertThat(invoke("{\"start\":\"9:00\",\"end\":\"08:00\"}", at(23, 0))).isNull();
    }

    // ---- start==end 空窗防御 ----

    @Test
    void start_equals_end_is_empty_window() {
        assertThat(invoke("{\"start\":\"08:00\",\"end\":\"08:00\"}", at(9, 0))).isNull();
    }

    // ---- 同日窗（非跨午夜）----

    @Test
    void same_day_window_inside_returns_today_end() {
        assertThat(wall(invoke("{\"start\":\"09:00\",\"end\":\"18:00\"}", at(12, 30))))
                .isEqualTo(LocalDateTime.of(2026, 6, 15, 18, 0));
    }

    @Test
    void same_day_window_start_boundary_is_inclusive() {
        assertThat(wall(invoke("{\"start\":\"09:00\",\"end\":\"18:00\"}", at(9, 0))))
                .isEqualTo(LocalDateTime.of(2026, 6, 15, 18, 0));
    }

    @Test
    void same_day_window_end_boundary_is_exclusive() {
        assertThat(invoke("{\"start\":\"09:00\",\"end\":\"18:00\"}", at(18, 0))).isNull();
    }

    @Test
    void same_day_window_outside_is_not_muted() {
        assertThat(invoke("{\"start\":\"09:00\",\"end\":\"18:00\"}", at(7, 59))).isNull();
        assertThat(invoke("{\"start\":\"09:00\",\"end\":\"18:00\"}", at(19, 0))).isNull();
    }

    // ---- 跨午夜窗（22:00→08:00：t≥start 或 t<end）----

    @Test
    void cross_midnight_before_end_segment_returns_today_end() {
        assertThat(wall(invoke("{\"start\":\"22:00\",\"end\":\"08:00\"}", at(1, 30))))
                .isEqualTo(LocalDateTime.of(2026, 6, 15, 8, 0));
    }

    @Test
    void cross_midnight_after_start_segment_returns_next_day_end() {
        assertThat(wall(invoke("{\"start\":\"22:00\",\"end\":\"08:00\"}", at(23, 5))))
                .isEqualTo(LocalDateTime.of(2026, 6, 16, 8, 0));
    }

    @Test
    void cross_midnight_start_boundary_returns_next_day_end() {
        assertThat(wall(invoke("{\"start\":\"22:00\",\"end\":\"08:00\"}", at(22, 0))))
                .isEqualTo(LocalDateTime.of(2026, 6, 16, 8, 0));
    }

    @Test
    void cross_midnight_end_boundary_is_exclusive() {
        assertThat(invoke("{\"start\":\"22:00\",\"end\":\"08:00\"}", at(8, 0))).isNull();
    }

    @Test
    void cross_midnight_outside_is_not_muted() {
        assertThat(invoke("{\"start\":\"22:00\",\"end\":\"08:00\"}", at(12, 0))).isNull();
    }
}
