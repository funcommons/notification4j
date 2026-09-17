package fun.commons.notification4j.controller;

import fun.commons.notification4j.client.NfyOpsClient;
import fun.commons.notification4j.dto.PostJobsArchiveConsumesRequest;
import fun.commons.notification4j.dto.PostReconcileRequest;
import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequiresToken(value = "OPS", type = "access")
public class NfyOpsController {

    private final NfyOpsClient client;
    private final JdbcTemplate jdbc;

    @GetMapping("/benefit/api/v1/ops/health")
    public Object getHealth() {
        return client.getHealth();
    }

    @GetMapping("/benefit/api/v1/ops/metrics")
    public Object getMetrics() {
        return client.getMetrics();
    }

    @PostMapping("/benefit/api/v1/ops/cache/evict")
    public Object postCacheEvict(
            @Valid @RequestBody fun.commons.notification4j.dto.PostCacheEvictRequest req) {
        return client.postCacheEvict(req);
    }

    @PostMapping("/benefit/api/v1/ops/jobs/refresh-cycles")
    public Object postJobsRefreshCycles(
            @Valid @RequestBody fun.commons.notification4j.dto.PostJobsRefreshCyclesRequest req) {
        return client.postJobsRefreshCycles(req);
    }

    /**
     * 流水归档: 清理 nfya_consume 旧数据 (分区表冷热分离)。
     * <p>
     * 当前数据量小 (358 行), 接口就绪, 数据到量再跑。
     * dryRun=true 仅统计不删; 否则 DELETE beforeDate 前的流水。
     */
    @PostMapping("/benefit/api/v1/ops/jobs/archive-consumes")
    public Object postJobsArchiveConsumes(
            @Valid @RequestBody PostJobsArchiveConsumesRequest req) {
        OffsetDateTime before;
        try {
            before = OffsetDateTime.parse(req.getBeforeDate());
        } catch (Exception e) {
            return ApiResponse.fail(400, "beforeDate 格式非法 (需 ISO-8601)");
        }
        boolean dryRun = req.getDryRun() != null && req.getDryRun();

        StringBuilder sql = new StringBuilder("SELECT count(*) FROM nfya_consume WHERE created_at < ?");
        if (req.getTenantId() != null && !req.getTenantId().isBlank()) {
            sql.append(" AND tenant_id = ?");
        }
        Long count;
        if (req.getTenantId() != null && !req.getTenantId().isBlank()) {
            count = jdbc.queryForObject(sql.toString(), Long.class, before, Long.parseLong(req.getTenantId()));
        } else {
            count = jdbc.queryForObject(sql.toString(), Long.class, before);
        }

        int deleted = 0;
        if (!dryRun && count > 0) {
            StringBuilder del = new StringBuilder("DELETE FROM nfya_consume WHERE created_at < ?");
            if (req.getTenantId() != null && !req.getTenantId().isBlank()) {
                del.append(" AND tenant_id = ?");
                deleted = jdbc.update(del.toString(), before, Long.parseLong(req.getTenantId()));
            } else {
                deleted = jdbc.update(del.toString(), before);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("before_date", req.getBeforeDate());
        result.put("tenant_id", req.getTenantId());
        result.put("dry_run", dryRun);
        result.put("archived_count", dryRun ? count : deleted);
        return ApiResponse.success(result);
    }

    /**
     * 对账: 三方订单 vs notification4j nfya_consume 流水比对。
     * 按 external_order_id 查 consume, 比对 status + consume_num, 输出差异报告。
     */
    @PostMapping("/benefit/api/v1/ops/reconcile")
    public Object postReconcile(@Valid @RequestBody PostReconcileRequest req) {
        List<Map<String, Object>> differences = new ArrayList<>();
        int matched = 0;
        for (PostReconcileRequest.ReconcileOrder order : req.getOrders()) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT status, consume_num FROM nfya_consume WHERE external_order_id = ? AND tenant_id = ? AND is_deleted = 0",
                    order.getExternalOrderId(), req.getTenantId());
            if (rows.isEmpty()) {
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("external_order_id", order.getExternalOrderId());
                diff.put("type", "MISSING");
                diff.put("detail", "notification4j 无此订单流水");
                differences.add(diff);
                continue;
            }
            Map<String, Object> consume = rows.get(0);
            String actualStatus = (String) consume.get("status");
            int actualNum = ((Number) consume.get("consume_num")).intValue();
            boolean statusMatch = order.getExpectedStatus() == null
                    || order.getExpectedStatus().equalsIgnoreCase(actualStatus);
            boolean numMatch = order.getExpectedConsumeNum() == null
                    || order.getExpectedConsumeNum() == actualNum;
            if (statusMatch && numMatch) {
                matched++;
            } else {
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("external_order_id", order.getExternalOrderId());
                diff.put("type", !statusMatch ? "STATUS_MISMATCH" : "NUM_MISMATCH");
                diff.put("expected_status", order.getExpectedStatus());
                diff.put("actual_status", actualStatus);
                diff.put("expected_num", order.getExpectedConsumeNum());
                diff.put("actual_num", actualNum);
                differences.add(diff);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", req.getOrders().size());
        result.put("matched", matched);
        result.put("differences", differences);
        result.put("difference_count", differences.size());
        return ApiResponse.success(result);
    }
}
