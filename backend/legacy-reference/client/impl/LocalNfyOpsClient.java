package fun.commons.notification4j.client.impl;

import fun.commons.notification4j.client.NfyOpsClient;
import fun.commons.notification4j.service.NfyOpsService;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class LocalNfyOpsClient implements NfyOpsClient {

    private final NfyOpsService service;

    @Override
    public Object getHealth() {
        return service.getHealth();
    }

    @Override
    public Object getMetrics() {
        return service.getMetrics();
    }

    @Override
    public Object postCacheEvict(fun.commons.notification4j.dto.PostCacheEvictRequest req) {
        return service.postCacheEvict(req);
    }

    @Override
    public Object postJobsRefreshCycles(fun.commons.notification4j.dto.PostJobsRefreshCyclesRequest req) {
        return service.postJobsRefreshCycles(req);
    }

}
