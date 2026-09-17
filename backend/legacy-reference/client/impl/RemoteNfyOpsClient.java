package fun.commons.notification4j.client.impl;

import fun.commons.notification4j.client.NfyOpsClient;
import fun.commons.notification4j.dto.PostCacheEvictRequest;
import fun.commons.notification4j.dto.PostJobsRefreshCyclesRequest;
import fun.commons.notification4j.properties.NfyProperties;
import fun.commons.framework4j.transport.HttpTransport;

/**
 * remote 模式 ops client (跨进程调远端 notification4j ops)。
 */
public class RemoteNfyOpsClient extends AbstractRemoteNfyClient implements NfyOpsClient {

    public RemoteNfyOpsClient(NfyProperties properties, HttpTransport transport) {
        super(properties, transport);
    }

    @Override
    public Object getHealth() {
        return invoke("/benefit/api/v1/ops/health", "GET", null, null);
    }

    @Override
    public Object getMetrics() {
        return invoke("/benefit/api/v1/ops/metrics", "GET", null, null);
    }

    @Override
    public Object postCacheEvict(PostCacheEvictRequest req) {
        return invoke("/benefit/api/v1/ops/cache/evict", "POST", null, req);
    }

    @Override
    public Object postJobsRefreshCycles(PostJobsRefreshCyclesRequest req) {
        return invoke("/benefit/api/v1/ops/jobs/refresh-cycles", "POST", null, req);
    }
}
