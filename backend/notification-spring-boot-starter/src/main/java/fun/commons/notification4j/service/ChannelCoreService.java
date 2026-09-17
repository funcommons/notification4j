package fun.commons.notification4j.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.dto.PatchChannelsChannelIdRequest;
import fun.commons.notification4j.dto.PostChannelsRequest;
import fun.commons.notification4j.entity.NfyaChannel;
import fun.commons.notification4j.mapper.NfyaChannelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 渠道域公共核心（评审第 10~16 步 P2-1：ChannelService/ChannelAdminService 近似复制收敛）。
 * 用户渠道（{@link ChannelService}，scope=USER，§5.7 runtime 面）与公共渠道
 * （{@link ChannelAdminService}，scope=TENANT，§5.9.2 admin 面）的
 * register/verify/patch/owner 校验同构，差异仅三处以 scope 参数收口：
 * 1. userid 维度：USER 按请求方 userid；TENANT 固定 userid=''（租户公共资源）；
 * 2. 同类型 ≤5 上限仅 USER（10605，count-then-insert 软限），TENANT 不设上限；
 * 3. owner 校验 10400 文案按面区分（USER「资源不存在或无权访问」/ TENANT「渠道不存在」，
 *    防探测同码 §6.3），由调用方传入。
 * 既有口径不变：EMAIL 归一化 trim+lowercase（防 A@x.com/a@x.com 变体绕过 md5(target) 唯一闸）、
 * target 校验与验证外呼均在调用方事务之外（SSRF DNS 解析/HTTP 外呼最长 5s，评审第 4 步 P2：
 * 外呼占事务会耗尽连接池——本类不自开事务，事务边界收在调用方 @Transactional 公开方法）、
 * 同 target 重复由 md5(target) 唯一闸兜底 10401、secret/ext 密文与 jsonb 列走实体 typeHandler。
 * 数据访问统一走 NfyaChannelMapper（原 ServiceImpl 的 save/getById/updateById 即委托同一
 * mapper 方法，等价变换）。
 */
@Service
@RequiredArgsConstructor
public class ChannelCoreService {

    public static final String SCOPE_USER = "USER";
    public static final String SCOPE_TENANT = "TENANT";

    private static final int MAX_CHANNELS_PER_TYPE = 5;

    private final NfyaChannelMapper channelMapper;
    private final WebhookTargetValidator targetValidator;
    private final ChannelVerifier channelVerifier;

    /**
     * 注册公共核心：target 校验（IM 违规 10609 / EMAIL 格式与参数非法 10100）→
     * 同类型上限（仅 USER，10605）→ insert 落库 PENDING（不发验证消息，P99≤300ms）→
     * 同 target 重复 10401。
     * 不占事务：SSRF 的 DNS 解析最长可到数秒，事务内等待会占满连接池（评审第 4 步 P2）；
     * 单条 insert 自身原子，并发重复由唯一闸兜底。上限命中时同 target 重复注册返回 10605
     * 而非 10401，属可接受次序偏差（评审记录：不引入 advisory lock，超限无资金/安全后果）。
     */
    public Map<String, Object> register(long tenantId, String userid, String scope, PostChannelsRequest req) {
        targetValidator.validate(req.channelType(), req.target());
        if (SCOPE_USER.equals(scope)) {
            Long count = channelMapper.selectCount(new LambdaQueryWrapper<NfyaChannel>()
                    .eq(NfyaChannel::getTenantId, tenantId)
                    .eq(NfyaChannel::getUserid, userid)
                    .eq(NfyaChannel::getScope, scope)
                    .eq(NfyaChannel::getChannelType, req.channelType()));
            if (count != null && count >= MAX_CHANNELS_PER_TYPE) {
                throw new ApiException(10605, "同类型渠道数量超限(≤5)");
            }
        }
        NfyaChannel ch = new NfyaChannel();
        ch.setTenantId(tenantId);
        ch.setScope(scope);
        ch.setUserid(userid);
        ch.setChannelType(req.channelType());
        ch.setName(req.name());
        // EMAIL 归一化（trim+lowercase），防 A@x.com/a@x.com 变体绕过 md5(target) 唯一闸（评审第 4 步 P2）
        ch.setTarget("EMAIL".equals(req.channelType())
                ? req.target().trim().toLowerCase() : req.target());
        ch.setSecret(req.secret() == null ? "" : req.secret());
        ch.setKeyword(req.keyword() == null ? "" : req.keyword());
        ch.setStatus("PENDING");
        ch.setFailCount(0);
        ch.setExt("{}");
        try {
            channelMapper.insert(ch);
        } catch (DuplicateKeyException e) {
            throw new ApiException(10401, "该渠道已存在");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channel_id", String.valueOf(ch.getId()));
        data.put("status", ch.getStatus());
        data.put("verify_tip", "请调用 verify 接口发送验证消息");
        return data;
    }

    /**
     * 验证公共核心（天然幂等，免 Idempotency-Key）：owner 校验 10400 → ChannelVerifier
     * （成功判定 = HTTP 2xx 且渠道业务码成功，外呼最长 5s 在事务外）→
     * ENABLED + fail_count 清零 + last_verify_at（状态落库短事务）。
     */
    public Map<String, Object> verify(long tenantId, String userid, String scope, String channelId,
                                      String notFoundMessage) {
        NfyaChannel ch = requireOwned(tenantId, userid, scope, channelId, notFoundMessage);
        channelVerifier.verify(ch);
        ch.setStatus("ENABLED");
        ch.setFailCount(0);
        ch.setLastVerifyAt(OffsetDateTime.now());
        channelMapper.updateById(ch);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channel_id", channelId);
        data.put("status", "ENABLED");
        data.put("last_verify_at", ch.getLastVerifyAt().toInstant().toEpochMilli());
        return data;
    }

    /**
     * 改名/启停公共核心：10610 判定收口一处——ENABLED 前置已验证（last_verify_at 非空），
     * 熔断态（连续失败 fail_count≥5 自动停用）须先重新验证。调用方 @Transactional 短事务。
     */
    public Map<String, Object> patch(long tenantId, String userid, String scope, String channelId,
                                     PatchChannelsChannelIdRequest req, String notFoundMessage) {
        NfyaChannel ch = requireOwned(tenantId, userid, scope, channelId, notFoundMessage);
        if (req.name() != null && !req.name().isBlank()) {
            ch.setName(req.name());
        }
        if (req.status() != null && !req.status().isBlank()) {
            if (!"ENABLED".equals(req.status()) && !"DISABLED".equals(req.status())) {
                throw new ApiException(10100, "status 仅允许 ENABLED/DISABLED");
            }
            if ("ENABLED".equals(req.status())
                    && (ch.getLastVerifyAt() == null || ch.getFailCount() != null && ch.getFailCount() >= 5)) {
                throw new ApiException(10610, "渠道连续失败已自动停用，请重新验证");
            }
            ch.setStatus(req.status());
        }
        channelMapper.updateById(ch);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channel_id", channelId);
        data.put("status", ch.getStatus());
        return data;
    }

    /**
     * owner 校验公共核心（跨租户/跨 scope/已删同一 10400，防探测 §6.3）：scope 比对收口此处；
     * USER 面额外比对 userid（userid=null 表示不比对，TENANT 面口径）。
     */
    public NfyaChannel requireOwned(long tenantId, String userid, String scope, String channelId,
                                    String notFoundMessage) {
        long id;
        try {
            id = Long.parseLong(channelId);
        } catch (NumberFormatException e) {
            throw new ApiException(10400, notFoundMessage);
        }
        NfyaChannel ch = channelMapper.selectById(id);
        if (ch == null || ch.getTenantId() != tenantId || !scope.equals(ch.getScope())
                || userid != null && !userid.equals(ch.getUserid())) {
            throw new ApiException(10400, notFoundMessage);
        }
        return ch;
    }
}
