package fun.commons.notification4j.client;

public interface NfyOpsClient {
    // 存活探针(检查 DB/Redis 健康度)
    Object getHealth();

    // 暴露供 Prometheus 抓取的打点数据
    Object getMetrics();

    // 紧急手动淘汰/降级失效异常缓存
    Object postCacheEvict(@org.springframework.web.bind.annotation.RequestBody fun.commons.notification4j.dto.PostCacheEvictRequest req);

    // 紧急手动触发分布式周期结转定时任务
    Object postJobsRefreshCycles(@org.springframework.web.bind.annotation.RequestBody fun.commons.notification4j.dto.PostJobsRefreshCyclesRequest req);

}
