package fun.commons.notification4j.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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
 * 渠道域（F-CHN-001，§5.7/§5.9.1）。
 * 注册：仅落库 PENDING（不发验证消息，P99≤300ms）；同 target 10401；同类型 ≥5 10605。
 * 验证：owner 校验 10400 → ChannelVerifier（HTTP 2xx 且业务码成功）→ ENABLED + fail_count 清零。
 * 启停：ENABLED 前置已验证（last_verify_at 非空），未验证/熔断态 → 10610。
 * 删除：逻辑删；订阅剔除在第 4b 步（订阅矩阵）接入。
 * register/verify/patch/owner 校验为与 ChannelAdminService（scope=TENANT）的同构复制，
 * 已收敛至 {@link ChannelCoreService} 并以 scope=USER 委托（评审第 10~16 步 P2-1）；
 * 本类保留 USER 面差异口径：我的渠道列表（userid 维度）与删除的订阅级联剔除。
 */
@Service
@RequiredArgsConstructor
public class ChannelService extends ServiceImpl<NfyaChannelMapper, NfyaChannel> {

    /** owner 校验 10400 文案（USER 面防探测同码 §6.3） */
    private static final String NOT_FOUND = "资源不存在或无权访问";

    private final ChannelCoreService channelCore;
    private final SubscriptionService subscriptionService;

    /**
     * 注册渠道（不占事务：SSRF 的 DNS 解析最长可到数秒，事务内等待会占满连接池——评审第 4 步 P2；
     * 单条 insert 自身原子，并发重复由 md5(target) 唯一闸兜底 10401。流程见 ChannelCoreService#register）
     */
    public Map<String, Object> register(long tenantId, String userid, PostChannelsRequest req) {
        return channelCore.register(tenantId, userid, ChannelCoreService.SCOPE_USER, req);
    }

    /** 我的渠道列表（target 脱敏输出） */
    public Map<String, Object> list(long tenantId, String userid) {
        var rows = lambdaQuery()
                .eq(NfyaChannel::getTenantId, tenantId)
                .eq(NfyaChannel::getUserid, userid)
                .eq(NfyaChannel::getScope, "USER")
                .orderByDesc(NfyaChannel::getCreatedAt)
                .list();
        var list = rows.stream().map(ch -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("channel_id", String.valueOf(ch.getId()));
            item.put("channel_type", ch.getChannelType());
            item.put("name", ch.getName());
            item.put("target", mask(ch));
            item.put("status", ch.getStatus());
            item.put("fail_count", ch.getFailCount());
            item.put("last_verify_at", ch.getLastVerifyAt() == null ? null : ch.getLastVerifyAt().toInstant().toEpochMilli());
            return item;
        }).toList();
        return Map.of("list", list);
    }

    /**
     * 验证渠道（天然幂等，免 Idempotency-Key）：HTTP 外呼（最长 5s）在事务外，
     * 仅状态落库为短事务（评审第 4 步 P2：外呼占事务会耗尽连接池）；
     * 成功 → ENABLED + fail_count 清零 + last_verify_at。流程见 ChannelCoreService#verify。
     */
    public Map<String, Object> verify(long tenantId, String userid, String channelId) {
        return channelCore.verify(tenantId, userid, ChannelCoreService.SCOPE_USER, channelId, NOT_FOUND);
    }

    /** 改名/启停：ENABLED 须已验证（含熔断态须先重新验证）→ 10610（判定收口 ChannelCoreService#patch） */
    @Transactional
    public Map<String, Object> patch(long tenantId, String userid, String channelId, PatchChannelsChannelIdRequest req) {
        return channelCore.patch(tenantId, userid, ChannelCoreService.SCOPE_USER, channelId, req, NOT_FOUND);
    }

    /** 删除渠道（逻辑删；级联剔除订阅中的失效 id，行回落 ["INAPP"]） */
    @Transactional
    public Map<String, Object> delete(long tenantId, String userid, String channelId) {
        channelCore.requireOwned(tenantId, userid, ChannelCoreService.SCOPE_USER, channelId, NOT_FOUND);
        removeById(Long.valueOf(channelId));
        subscriptionService.removeChannel(tenantId, userid, channelId);
        return Map.of("channel_id", channelId);
    }

    /** 脱敏：webhook 保留 scheme+host、查询串全遮蔽；飞书凭证在 path 末段（/hook/{token}）一并遮蔽；邮箱 a***@domain */
    static String mask(NfyaChannel ch) {
        String target = ch.getTarget();
        if ("EMAIL".equals(ch.getChannelType())) {
            int at = target.indexOf('@');
            return at <= 0 ? "***" : target.charAt(0) + "***" + target.substring(at);
        }
        if ("FEISHU".equals(ch.getChannelType())) {
            int lastSlash = target.lastIndexOf('/');
            if (lastSlash > target.indexOf("//") + 1) {
                return target.substring(0, lastSlash) + "/****";
            }
        }
        int q = target.indexOf('?');
        return q > 0 ? target.substring(0, q) + "?****" : target;
    }
}
