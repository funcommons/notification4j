package fun.commons.notification4j.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import fun.commons.notification4j.entity.NfyaChannel;
import fun.commons.notification4j.entity.NfyaDelivery;
import fun.commons.notification4j.entity.NfyaMessage;
import fun.commons.notification4j.entity.NfyaMessageRecipient;
import fun.commons.notification4j.mapper.NfyaChannelMapper;
import fun.commons.notification4j.mapper.NfyaDeliveryMapper;
import fun.commons.notification4j.mapper.NfyaMessageMapper;
import fun.commons.notification4j.mapper.NfyaMessageRecipientMapper;
import fun.commons.notification4j.properties.NfyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.context.SmartLifecycle;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 外发引擎（技术方案 §4.3；第 6b 步）：
 * claim（SKIP LOCKED 领取+置 SENDING 短事务）→ 按渠道并行投递（SPI）→ 结果回写
 * （SUCCESS 清渠道 fail_count / FAILED 退避 1,5,15min 重试 ≤3 次 / 超 3 次 DEAD）；
 * 渠道连续失败 ≥5 → 熔断 DISABLED + 属主站内信；Reaper 回收 SENDING 超时行。
 * at-least-once：reaper 回收或崩溃重投可能致渠道侧重复，契约可接受。
 * 调度开关 nfy.engine.enabled（默认 false，IT/独立部署显式开）。
 */
@Slf4j
public class DeliveryEngine implements SmartLifecycle {

    private static final int MAX_RETRY = 3;
    private static final int BREAKER_THRESHOLD = 5;
    private static final java.util.concurrent.atomic.AtomicInteger WORKER_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 每渠道固定窗限速：窗内计数 < 上限 → 占额返回 true；否则 false（本次归还延后再投） */
    private boolean tryAcquireChannelQuota(Long channelId) {
        int limit = conf.getRateLimitPerMinute();
        if (limit <= 0) {
            return true; // 0=不限
        }
        long nowMinute = System.currentTimeMillis() / 60000L;
        long[] window = rateWindows.computeIfAbsent(channelId, k -> new long[]{nowMinute, 0});
        synchronized (window) {
            if (window[0] != nowMinute) {
                window[0] = nowMinute;
                window[1] = 0;
            }
            if (window[1] >= limit) {
                return false;
            }
            window[1]++;
            return true;
        }
    }

    private final NfyaDeliveryMapper deliveryMapper;
    private final NfyaChannelMapper channelMapper;
    private final NfyaMessageMapper messageMapper;
    private final NfyaMessageRecipientMapper recipientMapper;
    private final DeliveryClaimService claimService;
    private final NfyProperties.Engine conf;
    /** 有序：内建在前、业务方自定义在后；resolve 反向匹配 → 自定义覆盖内建 */
    private final List<ChannelSender> senderChain = new java.util.ArrayList<>();
    /** 每渠道限速固定窗（评审第 6b 步 P1-4；V1.0 单机内存窗，多实例合计超限为已登记边界） */
    private final Map<Long, long[]> rateWindows = new java.util.concurrent.ConcurrentHashMap<>();
    private final ExecutorService workers;

    private ThreadPoolTaskScheduler scheduler;
    private volatile boolean running;

    public DeliveryEngine(NfyaDeliveryMapper deliveryMapper, NfyaChannelMapper channelMapper,
                          NfyaMessageMapper messageMapper, NfyaMessageRecipientMapper recipientMapper,
                          DeliveryClaimService claimService, NfyProperties properties,
                          List<ChannelSender> builtinSenders,
                          org.springframework.beans.factory.ObjectProvider<ChannelSender> customSenders) {
        this.deliveryMapper = deliveryMapper;
        this.channelMapper = channelMapper;
        this.messageMapper = messageMapper;
        this.recipientMapper = recipientMapper;
        this.claimService = claimService;
        this.conf = properties.getEngine();
        int workers = Math.max(1, conf.getWorkerCount());
        this.workers = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "nfy-engine-worker-" + WORKER_SEQ.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        senderChain.addAll(builtinSenders);
        for (ChannelSender s : customSenders) {
            senderChain.add(s);
        }
    }

    private ChannelSender resolveSender(String channelType) {
        for (int i = senderChain.size() - 1; i >= 0; i--) {
            if (senderChain.get(i).supports(channelType)) {
                return senderChain.get(i);
            }
        }
        return null;
    }

    @Override
    public void start() {
        if (!conf.isEnabled()) {
            return;
        }
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("nfy-engine-");
        scheduler.initialize();
        scheduler.scheduleAtFixedRate(this::scanOnce, java.time.Duration.ofMillis(conf.getScanIntervalMs()));
        scheduler.scheduleAtFixedRate(this::reapOnce, java.time.Duration.ofMillis(conf.getReaperIntervalMs()));
        running = true;
        log.info("[Engine] 外发引擎已启动 scan={}ms batch={} reaper={}ms",
                conf.getScanIntervalMs(), conf.getBatchSize(), conf.getReaperIntervalMs());
    }

    @Override
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdown();
        }
        workers.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 单轮扫描（调度入口）：领取 → 并行投递 → 回写 */
    public void scanOnce() {
        if (!running) {
            return;
        }
        List<NfyaDelivery> claimed = claimService.claim(conf.getBatchSize());
        for (NfyaDelivery d : claimed) {
            workers.submit(() -> {
                try {
                    deliverOne(d);
                } catch (Exception e) {
                    log.warn("[Engine] 投递异常 delivery={}", d.getId(), e);
                    revertToPending(d);
                }
            });
        }
    }

    /** Reaper：SENDING 超时回收 PENDING（at-least-once） */
    public void reapOnce() {
        if (!running) {
            return;
        }
        try {
            List<NfyaDelivery> stale = deliveryMapper.findStaleSending(
                    OffsetDateTime.now().minus(java.time.Duration.ofMillis(conf.getSendingStaleMs())), conf.getBatchSize());
            for (NfyaDelivery d : stale) {
                deliveryMapper.update(null, new LambdaUpdateWrapper<NfyaDelivery>()
                        .eq(NfyaDelivery::getId, d.getId())
                        .eq(NfyaDelivery::getStatus, "SENDING")
                        .set(NfyaDelivery::getStatus, "PENDING")
                        .set(NfyaDelivery::getNextRetryAt, OffsetDateTime.now()));
            }
            if (!stale.isEmpty()) {
                log.warn("[Engine] reaper 回收 SENDING 超时行 {} 条", stale.size());
            }
        } catch (Exception e) {
            log.warn("[Engine] reaper 异常: {}", e.getMessage());
        }
    }

    /** 单行投递 + 结果回写（短事务语义：外呼无事务，回写单语句原子） */
    void deliverOne(NfyaDelivery d) {
        if (!tryAcquireChannelQuota(d.getChannelId())) {
            // 超渠道限速：归还并延后（不计失败重试）
            revertToPending(d);
            return;
        }
        NfyaChannel ch = channelMapper.selectById(d.getChannelId());
        if (ch == null || !"ENABLED".equals(ch.getStatus())) {
            // 领取后渠道被停用/删除：归还 PENDING 延后再试
            revertToPending(d);
            return;
        }
        ChannelSender sender = resolveSender(d.getChannelType());
        ChannelSender.SendResult result = sender == null
                ? ChannelSender.SendResult.fail("无适配器: " + d.getChannelType())
                : sender.send(d, ch);
        if (result.ok()) {
            // CAS 守卫（评审第 6b 步 P1）：仅当仍为 SENDING 时回写，防 reaper 回收后的陈旧 worker 覆盖新状态
            int updated = deliveryMapper.update(null, new LambdaUpdateWrapper<NfyaDelivery>()
                    .eq(NfyaDelivery::getId, d.getId())
                    .eq(NfyaDelivery::getStatus, "SENDING")
                    .set(NfyaDelivery::getStatus, "SUCCESS")
                    .set(NfyaDelivery::getSentAt, OffsetDateTime.now())
                    .set(NfyaDelivery::getErrorMessage, ""));
            if (updated > 0) {
                channelMapper.update(null, new LambdaUpdateWrapper<NfyaChannel>()
                        .eq(NfyaChannel::getId, ch.getId())
                        .set(NfyaChannel::getFailCount, 0));
            }
            return;
        }
        onSendFailure(d, ch, result.summary());
    }

    private void onSendFailure(NfyaDelivery d, NfyaChannel ch, String summary) {
        // 投递行重试计数（单任务）与渠道 fail_count（熔断）两类计数器分离（§4.3）
        channelMapper.update(null, new LambdaUpdateWrapper<NfyaChannel>()
                .eq(NfyaChannel::getId, ch.getId())
                .setSql("fail_count = fail_count + 1"));
        int newRetry = (d.getRetryCount() == null ? 0 : d.getRetryCount()) + 1;
        if (newRetry > MAX_RETRY) {
            updateIfSending(d.getId(), "DEAD", newRetry, summary, null);
        } else {
            long backoff = backoffSeconds()[Math.min(newRetry, backoffSeconds().length) - 1];
            updateIfSending(d.getId(), "FAILED", newRetry, summary, OffsetDateTime.now().plusSeconds(backoff));
        }
        maybeBreakChannel(ch.getId(), ch.getTenantId(), ch.getScope(), ch.getUserid());
    }

    /** 回写统一 CAS：仅 SENDING 态可迁移（0 行更新=已被 reaper/重投接管，放弃） */
    private void updateIfSending(Long id, String status, int retryCount, String summary, OffsetDateTime nextRetry) {
        LambdaUpdateWrapper<NfyaDelivery> uw = new LambdaUpdateWrapper<NfyaDelivery>()
                .eq(NfyaDelivery::getId, id)
                .eq(NfyaDelivery::getStatus, "SENDING")
                .set(NfyaDelivery::getStatus, status)
                .set(NfyaDelivery::getRetryCount, retryCount)
                .set(NfyaDelivery::getErrorMessage, truncate(summary));
        if (nextRetry != null) {
            uw.set(NfyaDelivery::getNextRetryAt, nextRetry);
        }
        deliveryMapper.update(null, uw);
    }

    /** 熔断：连续失败 ≥5 → DISABLED + 属主站内信（公共渠道无属主，仅日志）；单语句 CAS 防 TOFU（评审 P2） */
    private void maybeBreakChannel(Long channelId, Long tenantId, String scope, String userid) {
        NfyaChannel fresh = channelMapper.selectById(channelId);
        if (fresh == null || fresh.getFailCount() == null || fresh.getFailCount() < BREAKER_THRESHOLD) {
            return;
        }
        int affected = channelMapper.update(null, new LambdaUpdateWrapper<NfyaChannel>()
                .eq(NfyaChannel::getId, channelId)
                .eq(NfyaChannel::getStatus, "ENABLED")
                .apply("fail_count >= {0}", BREAKER_THRESHOLD)
                .set(NfyaChannel::getStatus, "DISABLED"));
        if (affected == 0) {
            return; // 已被并发熔断/验证成功清零
        }
        log.warn("[Engine] 渠道 {} 连续失败 {} 次，已熔断 DISABLED", channelId, fresh.getFailCount());
        if ("USER".equals(scope) && userid != null && !userid.isBlank()) {
            insertOwnerInbox(tenantId, userid, channelId);
        }
    }

    /** 属主站内信（绕过类型校验直插）：biz_no 带熔断纪元（评审 P1：固定 biz_no 会让二次熔断撞 uk 静默丢信） */
    private void insertOwnerInbox(Long tenantId, String userid, Long channelId) {
        try {
            NfyaMessage m = new NfyaMessage();
            m.setTenantId(tenantId);
            m.setBizNo("channel-break-" + channelId + "-" + System.currentTimeMillis());
            m.setTypeCode("CHANNEL_ALERT");
            m.setLevel("URGENT");
            m.setTitle("渠道连续失败已自动停用");
            m.setContent("您的渠道 " + channelId + " 连续投递失败已熔断停用，请重新验证后启用。");
            m.setLinkUrl("");
            m.setTemplateId(0L);
            m.setParams("{}");
            m.setReceiverCount(1);
            m.setStatus("SENT");
            m.setSender("ENGINE");
            m.setExt("{}");
            messageMapper.insert(m);
            NfyaMessageRecipient r = new NfyaMessageRecipient();
            r.setTenantId(tenantId);
            r.setMessageId(m.getId());
            r.setUserid(userid);
            r.setReadStatus("UNREAD");
            r.setExt("{}");
            recipientMapper.insert(r);
        } catch (Exception e) {
            log.warn("[Engine] 属主站内信写入失败 channel={} : {}", channelId, e.getMessage());
        }
    }

    private void revertToPending(NfyaDelivery d) {
        // CAS：仅 SENDING 态归还（评审第 6b 步 P1）
        deliveryMapper.update(null, new LambdaUpdateWrapper<NfyaDelivery>()
                .eq(NfyaDelivery::getId, d.getId())
                .eq(NfyaDelivery::getStatus, "SENDING")
                .set(NfyaDelivery::getStatus, "PENDING")
                .set(NfyaDelivery::getNextRetryAt, OffsetDateTime.now().plusSeconds(60)));
    }

    private long[] backoffSeconds() {
        try {
            String[] parts = conf.getBackoffSeconds().split(",");
            long[] arr = new long[parts.length];
            for (int i = 0; i < parts.length; i++) {
                arr[i] = Long.parseLong(parts[i].trim());
            }
            return arr;
        } catch (Exception e) {
            return new long[]{60, 300, 900};
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
