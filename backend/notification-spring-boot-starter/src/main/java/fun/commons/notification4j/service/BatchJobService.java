package fun.commons.notification4j.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.dto.PostMessagesRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 批量发送 Job（API-MSG-002 + JOB-001，V1.1 契约提前落地；§5.9.1）。
 * submit 同步校验（空 10101/超 10 万 10102）→ 建 Redis job（TTL 48h）→ 立即返回 job_id+poll_url；
 * 后台每批 1000 人一条 message（首批复用 biz_no，后续批次加 -bN 后缀）逐批事务执行；
 * 终态 DONE/PARTIAL/FAILED，failed_items 截断 ≤100。
 * 线程池 2 守护线程；Redis 键 nfy:job:{id}。
 */
@Slf4j
@Service
public class BatchJobService {

    private static final int BATCH_SIZE = 1000;
    private static final int MAX_USERS = 100000;
    private static final int MAX_FAILED_ITEMS = 100;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MessageService messageService;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "nfy-batch-worker");
        t.setDaemon(true);
        return t;
    });

    public BatchJobService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                           MessageService messageService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.messageService = messageService;
    }

    /** MSG-002 提交批量发送（立即返回） */
    public Map<String, Object> submit(long tenantId, PostMessagesRequest dto) {
        if (dto.userIds() == null || dto.userIds().isEmpty()) {
            throw new ApiException(10101, "接收人列表不能为空");
        }
        List<String> users = dto.userIds().stream().distinct().toList();
        if (users.size() > MAX_USERS) {
            throw new ApiException(10102, "批量发送人数超限(≤100000)");
        }
        String jobId = UUID.randomUUID().toString().replace("-", "");
        // 首次状态写失败即拒绝受理（评审 P1：否则消息照发而 job 永不可查，副作用零凭证）
        writeJob(jobId, tenantId, "RUNNING", users.size(), 0, List.of(), true);
        long tid = tenantId;
        executor.submit(() -> {
            try {
                run(tid, jobId, dto, users);
            } catch (Exception e) {
                log.warn("[Batch] job {} 执行异常", jobId, e);
                writeJob(jobId, tid, "FAILED", users.size(), 0, List.of("internal error"), false);
            }
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("job_id", jobId);
        data.put("poll_url", "/nfy/api/v1/runtime/jobs/" + jobId);
        data.put("total", users.size());
        return data;
    }

    /** JOB-001 轮询（不存在/过期/跨租户一律 10400 防探测） */
    public Map<String, Object> get(long tenantId, String jobId) {
        String json = redisTemplate.opsForValue().get("nfy:job:" + jobId);
        if (json == null) {
            throw new ApiException(10400, "任务不存在或已过期");
        }
        try {
            Map<String, Object> state = objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class));
            Object owner = state.get("tenant_id");
            if (owner != null && Long.parseLong(String.valueOf(owner)) != tenantId) {
                throw new ApiException(10400, "任务不存在或已过期");
            }
            return state;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("job 状态解析失败", e);
        }
    }

    /** 异步执行：逐批事务；类型不存在等前置失败 → 整个 FAILED（契约口径） */
    private void run(long tenantId, String jobId, PostMessagesRequest dto, List<String> users) {
        int finished = 0;
        List<String> failed = new ArrayList<>();
        int batchNo = 0;
        for (int i = 0; i < users.size(); i += BATCH_SIZE) {
            batchNo++;
            List<String> batch = users.subList(i, Math.min(i + BATCH_SIZE, users.size()));
            // blank → 每批独立 UUID（"null-bN" 字面量陷阱，评审 P2）；显式 bizNo 批次加 -bN 后缀
            String bizNo = batchNo == 1 && dto.bizNo() != null && !dto.bizNo().isBlank()
                    ? dto.bizNo()
                    : (dto.bizNo() == null || dto.bizNo().isBlank()
                        ? UUID.randomUUID().toString().replace("-", "")
                        : dto.bizNo() + "-b" + batchNo);
            try {
                messageService.send(tenantId, new PostMessagesRequest(
                        bizNo, dto.typeCode(), dto.level(), batch, dto.title(), dto.content(), dto.linkUrl()));
                finished += batch.size();
            } catch (Exception e) {
                log.warn("[Batch] job {} 批次 {} 失败: {}", jobId, batchNo, e.getMessage());
                failed.addAll(batch);
                if (failed.size() > MAX_FAILED_ITEMS) {
                    failed = failed.subList(0, MAX_FAILED_ITEMS);
                }
            }
            writeJob(jobId, tenantId, "RUNNING", users.size(), finished, failed, false);
        }
        String status = failed.isEmpty() ? "DONE" : finished == 0 ? "FAILED" : "PARTIAL";
        // 终态写失败重试一次（P1-5：终态丢失=对账无据）
        try {
            writeJob(jobId, tenantId, status, users.size(), finished, failed, false);
        } catch (Exception e) {
            log.error("[Batch] job {} 终态写入失败，重试一次", jobId, e);
            writeJob(jobId, tenantId, status, users.size(), finished, failed, false);
        }
    }

    private void writeJob(String jobId, long tenantId, String status, int total, int finished,
                          List<String> failedItems, boolean failHard) {
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("job_id", jobId);
            state.put("tenant_id", tenantId); // 属主维度（评审 P1：防跨租户轮询泄漏 userid/规模）
            state.put("status", status);
            state.put("total", total);
            state.put("finished", finished);
            state.put("failed_items", failedItems);
            redisTemplate.opsForValue().set("nfy:job:" + jobId,
                    objectMapper.writeValueAsString(state), Duration.ofHours(48));
        } catch (Exception e) {
            if (failHard) {
                throw new IllegalStateException("job 状态写入失败，拒绝受理", e);
            }
            log.error("[Batch] job 状态写 Redis 失败 job={} status={}", jobId, status, e);
        }
    }
}
