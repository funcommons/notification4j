package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.TenantDomain;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.notification4j.service.BatchJobService;
import fun.commons.notification4j.util.NfyTenantContexts;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** API-JOB-001 异步 Job 轮询（§5.9.1；Redis 状态 TTL 48h） */
@TenantDomain
@RequiresToken(value = "TENANT", type = "access")
@RestController
@RequestMapping("/nfy/api/v1/runtime/jobs")
@RequiredArgsConstructor
public class NfyJobController {

    private final BatchJobService batchJobService;

    @GetMapping("/{job_id}")
    public ApiResponse<Map<String, Object>> job(@PathVariable("job_id") String jobId) {
        return ApiResponse.success(batchJobService.get(NfyTenantContexts.tenantId(), jobId));
    }
}
