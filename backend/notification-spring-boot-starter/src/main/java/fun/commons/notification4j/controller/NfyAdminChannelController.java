package fun.commons.notification4j.controller;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.tenant.annotation.TenantDomain;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.notification4j.dto.PatchChannelsChannelIdRequest;
import fun.commons.notification4j.dto.PostChannelsRequest;
import fun.commons.notification4j.service.ChannelAdminService;
import fun.commons.notification4j.util.NfyTenantContexts;
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
 * API-ACH-001~003 公共渠道管理（§4.3 admin 面；TENANT token）。
 * 注册路径双通道：异基包走 autoconfig（enable-api 开关），同基包走组件扫描（沿 benefit4j 惯例）。
 */
@TenantDomain
@RequiresToken(value = "TENANT", type = "access")
@RestController
@RequestMapping("/nfy/api/v1/admin/channels")
@RequiredArgsConstructor
public class NfyAdminChannelController {

    private final ChannelAdminService channelAdminService;

    /** ACH-001 列表 */
    @GetMapping
    public ApiResponse<Map<String, Object>> list() {
        return ApiResponse.success(channelAdminService.list(NfyTenantContexts.tenantId()));
    }

    /** ACH-001 注册公共渠道（body 无 userid） */
    @PostMapping
    public ApiResponse<Map<String, Object>> register(@Valid @RequestBody PostChannelsRequest req) {
        return ApiResponse.success(channelAdminService.register(NfyTenantContexts.tenantId(), req));
    }

    /** ACH-003 验证 */
    @PostMapping("/{channel_id}/verify")
    public ApiResponse<Map<String, Object>> verify(@PathVariable("channel_id") String channelId) {
        return ApiResponse.success(channelAdminService.verify(NfyTenantContexts.tenantId(), channelId));
    }

    /** ACH-002 修改 */
    @PatchMapping("/{channel_id}")
    public ApiResponse<Map<String, Object>> patch(@PathVariable("channel_id") String channelId,
                                                  @Valid @RequestBody PatchChannelsChannelIdRequest req) {
        return ApiResponse.success(channelAdminService.patch(NfyTenantContexts.tenantId(), channelId, req));
    }

    /** ACH-002 删除 */
    @DeleteMapping("/{channel_id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable("channel_id") String channelId) {
        return ApiResponse.success(channelAdminService.delete(NfyTenantContexts.tenantId(), channelId));
    }
}
