package fun.commons.notification4j.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.dto.PostMessagesRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 25 步 service 层 mock 单测：BatchJobService（V1.1 批量发送 Job）。
 * mock StringRedisTemplate + MessageService（异步 run 全链路可断言，verify 用 bounded timeout，
 * 不依赖真实时钟/顺序）。覆盖：submit 同步校验（10101 空列表/10102 超 10 万）/Redis 首写失败拒绝受理
 * （failHard）/job 状态机 JSON（RUNNING→DONE/PARTIAL/FAILED）/get 属主校验（跨租户 10400）/
 * biz_no 空白 → 每批独立 UUID（非 "null-bN" 字面量）/显式 bizNo 批次 -bN 后缀/failed_items 截断 ≤100。
 */
// VECTOR: TAG=step25-unit
class BatchJobServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final MessageService messageService = mock(MessageService.class);

    private BatchJobService newService() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        return new BatchJobService(redisTemplate, new ObjectMapper(), messageService);
    }

    private static PostMessagesRequest req(String bizNo, List<String> users) {
        return new PostMessagesRequest(bizNo, "OTC", "NORMAL", users, "标题", "内容", null);
    }

    // ---- submit 同步校验 ----

    @Test
    void submit_rejects_empty_user_list_10101() {
        assertThatThrownBy(() -> newService().submit(1L, req(null, List.of())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10101);
                    assertThat(e.getMessage()).contains("不能为空");
                });
    }

    @Test
    void submit_rejects_over_100k_users_10102() {
        List<String> users = java.util.stream.IntStream.range(0, 100_001)
                .mapToObj(i -> "u" + i).collect(Collectors.toList());
        assertThatThrownBy(() -> newService().submit(1L, req(null, users)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10102);
                    assertThat(e.getMessage()).contains("100000");
                });
    }

    @Test
    void submit_first_redis_write_failure_rejects_acceptance() {
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));
        assertThatThrownBy(() -> newService().submit(1L, req("BIZ", List.of("u1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("job 状态写入失败");
    }

    @Test
    void submit_writes_running_job_and_returns_poll_url() throws Exception {
        // 记录该 job 全部状态行写入（submit 同步写 RUNNING，异步 worker 再写 批次进度/DONE——次数与批次
        // 划分相关，verify(times(1)) 与竞态耦合会偶发 TooManyActualInvocations，改为收敛等待 + 全轨迹断言）
        java.util.List<String> writes = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        org.mockito.Mockito.doAnswer(inv -> {
            writes.add(inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        Map<String, Object> data = newService().submit(7L, req("BIZ", List.of("u1", "u2")));
        String jobId = (String) data.get("job_id");
        assertThat(jobId).matches("[0-9a-f]{32}");
        assertThat(data.get("poll_url")).isEqualTo("/nfy/api/v1/runtime/jobs/" + jobId);
        assertThat(data.get("total")).isEqualTo(2);
        // submit 返回前已同步写入首条状态行
        java.util.List<String> mineBeforeAsync = writes.stream()
                .filter(s -> s.contains(jobId)).collect(java.util.stream.Collectors.toList());
        assertThat(mineBeforeAsync).as("submit 应同步写 RUNNING 状态行").isNotEmpty();
        assertThat(mineBeforeAsync.get(0)).contains("\"status\":\"RUNNING\"").contains("\"tenant_id\":7");
        // 等待异步 worker 收敛到终态 DONE（deadline 轮询，不固定 sleep）
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline
                && writes.stream().noneMatch(s -> s.contains(jobId) && s.contains("\"status\":\"DONE\""))) {
            Thread.sleep(20);
        }
        java.util.List<String> mine = writes.stream()
                .filter(s -> s.contains(jobId)).collect(java.util.stream.Collectors.toList());
        assertThat(mine.get(mine.size() - 1)).as("job 应收敛到 DONE 终态").contains("\"status\":\"DONE\"");
    }

    // ---- get（属主校验 + 防探测）----

    @Test
    void get_returns_state_for_owner() {
        when(valueOps.get("nfy:job:abc")).thenReturn(
                "{\"job_id\":\"abc\",\"tenant_id\":7,\"status\":\"DONE\",\"total\":1,\"finished\":1,\"failed_items\":[]}");
        Map<String, Object> state = newService().get(7L, "abc");
        assertThat(state).containsEntry("status", "DONE").containsEntry("finished", 1);
    }

    @Test
    void get_missing_expired_or_cross_tenant_is_10400() {
        when(valueOps.get("nfy:job:gone")).thenReturn(null);
        assertThatThrownBy(() -> newService().get(7L, "gone"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
        when(valueOps.get("nfy:job:other")).thenReturn("{\"tenant_id\":9,\"status\":\"DONE\"}");
        assertThatThrownBy(() -> newService().get(7L, "other")) // 跨租户轮询
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
    }

    @Test
    void get_corrupt_state_json_throws_illegal_state() {
        when(valueOps.get("nfy:job:bad")).thenReturn("not-json{");
        assertThatThrownBy(() -> newService().get(7L, "bad"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("job 状态解析失败");
    }

    // ---- 异步 run：bizNo 策略与终态 ----

    @Test
    void run_blank_biz_no_uses_independent_uuid_not_null_literal() {
        when(messageService.send(anyLong(), any(PostMessagesRequest.class))).thenReturn(Map.of());
        newService().submit(1L, req("", List.of("u1", "u2"))); // blank → 每批独立 UUID

        ArgumentCaptor<PostMessagesRequest> cap = ArgumentCaptor.forClass(PostMessagesRequest.class);
        verify(messageService, timeout(2000)).send(eq(1L), cap.capture());
        assertThat(cap.getValue().bizNo()).matches("[0-9a-f]{32}"); // UUID 去横线，非 "null-bN"
    }

    @Test
    void run_explicit_biz_no_first_batch_reuses_then_appends_batch_suffix() {
        when(messageService.send(anyLong(), any(PostMessagesRequest.class))).thenReturn(Map.of());
        List<String> users = java.util.stream.IntStream.range(0, 1001)
                .mapToObj(i -> "u" + i).collect(Collectors.toList()); // 2 批（1000+1）
        newService().submit(1L, req("BZ", users));

        ArgumentCaptor<PostMessagesRequest> cap = ArgumentCaptor.forClass(PostMessagesRequest.class);
        verify(messageService, timeout(2000).times(2)).send(eq(1L), cap.capture());
        assertThat(cap.getAllValues().get(0).bizNo()).isEqualTo("BZ"); // 首批复用
        assertThat(cap.getAllValues().get(1).bizNo()).isEqualTo("BZ-b2"); // 后续 -bN
    }

    @Test
    void run_partial_failure_marks_partial_and_truncates_failed_items() {
        when(messageService.send(anyLong(), any(PostMessagesRequest.class)))
                .thenReturn(Map.of())
                .thenThrow(new ApiException(10101, "接收人列表不能为空")); // 第二批整批失败
        List<String> users = java.util.stream.IntStream.range(0, 1001)
                .mapToObj(i -> "u" + i).collect(Collectors.toList());
        newService().submit(1L, req("BZ", users));

        ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
        // 写序确定：submit 同步 1 + 每批 1 + 终态 1 = 4；等到第 4 次写出现再断言终态（无竞态窗口）
        verify(valueOps, timeout(5000).times(4)).set(anyString(), state.capture(), any(Duration.class));
        String finalState = state.getAllValues().get(3);
        assertThat(finalState).contains("\"status\":\"PARTIAL\"")
                .contains("\"failed_items\":[\"u1000\"]"); // 第二批 1 人
    }

    @Test
    void run_all_batches_failed_marks_failed() {
        when(messageService.send(anyLong(), any(PostMessagesRequest.class)))
                .thenThrow(new ApiException(10601, "消息类型不存在或已停用"));
        newService().submit(1L, req("BZ", List.of("u1")));

        ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
        verify(valueOps, timeout(5000).times(3)).set(anyString(), state.capture(), any(Duration.class));
        assertThat(state.getAllValues().get(2)).contains("\"status\":\"FAILED\"");
    }

    @Test
    void run_success_marks_done() {
        when(messageService.send(anyLong(), any(PostMessagesRequest.class))).thenReturn(Map.of());
        newService().submit(1L, req("BZ", List.of("u1")));

        ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
        verify(valueOps, timeout(5000).times(3)).set(anyString(), state.capture(), any(Duration.class));
        assertThat(state.getAllValues().get(2)).contains("\"status\":\"DONE\"").contains("\"finished\":1");
    }
}
