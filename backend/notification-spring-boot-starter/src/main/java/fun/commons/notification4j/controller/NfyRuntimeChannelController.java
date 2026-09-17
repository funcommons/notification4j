package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.TenantDomain;
import fun.commons.notification4j.util.NfyTenantContexts;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.notification4j.dto.PatchChannelsChannelIdRequest;
import fun.commons.notification4j.dto.PostChannelsRequest;
import fun.commons.notification4j.service.ChannelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * API-CHN-001/002/003/004/005 渠道域（租户域 runtime 面；TENANT token + X-User-Id）。
 * 注册路径双通道：异基包走 autoconfig（enable-api 开关），同基包走组件扫描（沿 benefit4j 惯例）。
 */
@TenantDomain
@RequiresToken(value = "TENANT", type = "access")
@RestController
@RequestMapping("/nfy/api/v1/runtime/channels")
@RequiredArgsConstructor
public class NfyRuntimeChannelController {

    private final ChannelService channelService;

    /** API-CHN-002 注册渠道（幂等：UNIQUE(tenant_id,userid,channel_type,md5(target)) 真闸 10401） */
    @PostMapping
    public ApiResponse<Map<String, Object>> register(@Valid @RequestBody PostChannelsRequest req) {
        return ApiResponse.success(channelService.register(tenantId(), NfyTenantContexts.userId(), req));
    }

    /** API-CHN-001 我的渠道列表 */
    @GetMapping
    public ApiResponse<Map<String, Object>> list() {
        return ApiResponse.success(channelService.list(tenantId(), NfyTenantContexts.userId()));
    }

    /** API-CHN-003 验证渠道（同步外呼，超时 5s） */
    @PostMapping("/{channel_id}/verify")
    public ApiResponse<Map<String, Object>> verify(@PathVariable("channel_id") String channelId) {
        return ApiResponse.success(channelService.verify(tenantId(), NfyTenantContexts.userId(), channelId));
    }

    /** API-CHN-004 改名/启停 */
    @PatchMapping("/{channel_id}")
    public ApiResponse<Map<String, Object>> patch(@PathVariable("channel_id") String channelId,
                                                  @Valid @RequestBody PatchChannelsChannelIdRequest req) {
        return ApiResponse.success(channelService.patch(tenantId(), NfyTenantContexts.userId(), channelId, req));
    }

    /** API-CHN-005 删除渠道（逻辑删） */
    @DeleteMapping("/{channel_id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable("channel_id") String channelId) {
        return ApiResponse.success(channelService.delete(tenantId(), NfyTenantContexts.userId(), channelId));
    }

    private static long tenantId() {
        return NfyTenantContexts.tenantId();
    }
}
