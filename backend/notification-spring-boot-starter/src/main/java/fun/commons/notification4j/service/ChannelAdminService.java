package fun.commons.notification4j.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import fun.commons.notification4j.dto.PatchChannelsChannelIdRequest;
import fun.commons.notification4j.dto.PostChannelsRequest;
import fun.commons.notification4j.entity.NfyaChannel;
import fun.commons.notification4j.mapper.NfyaChannelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公共渠道管理面（API-ACH-001~003，§4.3/§5.9.2；TENANT admin 面）。
 * 与用户渠道（§5.7）同构，差异：body 无 userid、scope=TENANT、无 ≤5 上限（租户公共资源）。
 * register/verify/patch/owner 校验为与 ChannelService（scope=USER）的同构复制，
 * 已收敛至 {@link ChannelCoreService} 并以 scope=TENANT 委托（评审第 10~16 步 P2-1）；
 * 本类保留 TENANT 面差异口径：公共渠道列表（无 userid 维度）。
 * 删除为逻辑删；已发布公告 channel_ids 中的死引用无害——外发展开时按 ENABLED 过滤
 * （DeliveryPlanService），无需级联清理（评审第 6a 步登记项在此口径下闭环）。
 */
@Service
@RequiredArgsConstructor
public class ChannelAdminService {

    /** owner 校验 10400 文案（TENANT admin 面防探测同码 §6.3） */
    private static final String NOT_FOUND = "渠道不存在";

    private final NfyaChannelMapper channelMapper;
    private final ChannelCoreService channelCore;

    /** ACH-001 注册公共渠道（scope=TENANT, userid=''；单条 insert 原子 + uk 真闸 10401） */
    public Map<String, Object> register(long tenantId, PostChannelsRequest req) {
        return channelCore.register(tenantId, "", ChannelCoreService.SCOPE_TENANT, req);
    }

    /** ACH-001 列表（scope=TENANT，target 脱敏） */
    public Map<String, Object> list(long tenantId) {
        var rows = channelMapper.selectList(new LambdaQueryWrapper<NfyaChannel>()
                .eq(NfyaChannel::getTenantId, tenantId)
                .eq(NfyaChannel::getScope, "TENANT")
                .orderByDesc(NfyaChannel::getCreatedAt));
        var list = rows.stream().map(ch -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("channel_id", String.valueOf(ch.getId()));
            item.put("channel_type", ch.getChannelType());
            item.put("name", ch.getName());
            item.put("target", ChannelService.mask(ch));
            item.put("status", ch.getStatus());
            item.put("fail_count", ch.getFailCount());
            item.put("last_verify_at", ch.getLastVerifyAt() == null ? null : ch.getLastVerifyAt().toInstant().toEpochMilli());
            return item;
        }).toList();
        return Map.of("list", list);
    }

    /** ACH-003 验证：外呼在事务外，状态落库短事务（口径同用户渠道） */
    public Map<String, Object> verify(long tenantId, String channelId) {
        return channelCore.verify(tenantId, null, ChannelCoreService.SCOPE_TENANT, channelId, NOT_FOUND);
    }

    /** ACH-002 修改（改名/启停；ENABLED 前置已验证，10610 判定收口 ChannelCoreService#patch） */
    @Transactional
    public Map<String, Object> patch(long tenantId, String channelId, PatchChannelsChannelIdRequest req) {
        return channelCore.patch(tenantId, null, ChannelCoreService.SCOPE_TENANT, channelId, req, NOT_FOUND);
    }

    /** ACH-002 删除（逻辑删；公告引用经外发展开过滤兜底） */
    @Transactional
    public Map<String, Object> delete(long tenantId, String channelId) {
        channelCore.requireOwned(tenantId, null, ChannelCoreService.SCOPE_TENANT, channelId, NOT_FOUND);
        channelMapper.deleteById(Long.valueOf(channelId));
        return Map.of("channel_id", channelId);
    }
}
