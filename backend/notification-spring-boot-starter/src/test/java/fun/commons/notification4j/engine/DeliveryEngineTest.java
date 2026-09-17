package fun.commons.notification4j.engine;

import com.baomidou.mybatisplus.core.conditions.ISqlSegment;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 第 26 步 engine 层单测：DeliveryEngine 状态机决策逻辑（纯 mock mapper + 桩 sender，零网络零 sleep）。
 * 断言面：CAS 回写 eq SENDING（0 行更新=跳过渠道清零）/退避 1-5-15min 与自定义/畸形配置回退、
 * ≤3 次重试 FAILED vs 第 4 次 DEAD（不再带 next_retry_at）、渠道 fail_count 累加与 ≥5 熔断
 * DISABLED CAS + 属主站内信（USER scope 才有，biz_no 带熔断纪元）、reaper 回收超时 SENDING、
 * 每渠道固定窗限速（超限归还且不再加载渠道）、senderChain 反向解析（自定义覆盖内建）。
 * <p>
 * 登记不可测部分：ThreadPoolTaskScheduler 周期调度与 newFixedThreadPool 并行度不在此验证
 * （start/stop 生命周期仅断言守卫位与关闭不抛——调度正确性由 IT 引擎用例实质覆盖）。
 */
// VECTOR: TAG=step26-unit
class DeliveryEngineTest {

    private final NfyaDeliveryMapper deliveryMapper = mock(NfyaDeliveryMapper.class);
    private final NfyaChannelMapper channelMapper = mock(NfyaChannelMapper.class);
    private final NfyaMessageMapper messageMapper = mock(NfyaMessageMapper.class);
    private final NfyaMessageRecipientMapper recipientMapper = mock(NfyaMessageRecipientMapper.class);
    private final DeliveryClaimService claimService = mock(DeliveryClaimService.class);
    private final NfyProperties props = new NfyProperties();
    private final NfyProperties.Engine conf = props.getEngine();
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ChannelSender> customSenders = mock(ObjectProvider.class);

    private DeliveryEngine engine;

    @BeforeAll
    static void initTableInfos() {
        EngineMockSupport.initTableInfo(NfyaDelivery.class, NfyaChannel.class,
                NfyaMessage.class, NfyaMessageRecipient.class);
    }

    // ---- 桩与夹具 ----

    /** 桩 sender：send 结果固定，summary 可用于断言被解析到的实例（内建 vs 自定义） */
    private static ChannelSender stubSender(String type, String okSummary, String failSummary) {
        return new ChannelSender() {
            @Override
            public String channelType() {
                return type;
            }

            @Override
            public SendResult send(NfyaDelivery d, NfyaChannel ch) {
                return okSummary == null ? SendResult.fail(failSummary) : SendResult.success(okSummary);
            }
        };
    }

    private DeliveryEngine newEngine(ChannelSender... builtin) {
        when(customSenders.iterator()).thenReturn(List.<ChannelSender>of().iterator());
        engine = new DeliveryEngine(deliveryMapper, channelMapper, messageMapper, recipientMapper,
                claimService, props, List.of(builtin), customSenders);
        return engine;
    }

    private static NfyaDelivery delivery(Long id, Long channelId, String channelType, Integer retryCount) {
        NfyaDelivery d = new NfyaDelivery();
        d.setId(id);
        d.setTenantId(1L);
        d.setChannelId(channelId);
        d.setChannelType(channelType);
        d.setRetryCount(retryCount);
        d.setStatus("SENDING");
        return d;
    }

    private static NfyaChannel channel(String status, Integer failCount, String scope, String userid) {
        NfyaChannel ch = new NfyaChannel();
        ch.setId(7L);
        ch.setTenantId(1L);
        ch.setStatus(status);
        ch.setFailCount(failCount);
        ch.setScope(scope);
        ch.setUserid(userid);
        return ch;
    }

    private void channelEnabled() {
        when(channelMapper.selectById(7L)).thenReturn(channel("ENABLED", 0, "TENANT", null));
    }

    // ---- wrapper 断言辅助 ----

    private static String whereOf(LambdaUpdateWrapper<?> w) {
        return w.getExpression().getNormal().stream().map(ISqlSegment::getSqlSegment)
                .collect(Collectors.joining(" "));
    }

    private static Map<String, Object> paramsOf(LambdaUpdateWrapper<?> w) {
        w.getSqlSegment(); // 触发渲染：eq/apply 段参数是渲染期惰性写入 paramNameValuePairs（set 是急解析）
        return w.getParamNameValuePairs();
    }

    private static List<OffsetDateTime> timesOf(LambdaUpdateWrapper<?> w) {
        return paramsOf(w).values().stream()
                .filter(OffsetDateTime.class::isInstance).map(OffsetDateTime.class::cast).toList();
    }

    // ---- 成功回写 CAS ----

    @Test
    void deliverOne_success_marks_success_via_sending_cas_and_resets_channel_fail_count() {
        newEngine(stubSender("DINGTALK", "ok", null));
        channelEnabled();
        when(deliveryMapper.update(isNull(), any())).thenReturn(1); // CAS 命中

        engine.deliverOne(delivery(1L, 7L, "DINGTALK", null));

        ArgumentCaptor<LambdaUpdateWrapper<NfyaDelivery>> cap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(deliveryMapper).update(isNull(), cap.capture());
        LambdaUpdateWrapper<NfyaDelivery> w = cap.getValue();
        assertThat(whereOf(w)).contains("id =").contains("status ="); // CAS 守卫：eq id + eq SENDING
        assertThat(paramsOf(w).containsValue("SENDING")).isTrue();
        assertThat(paramsOf(w).containsValue("SUCCESS")).isTrue();
        assertThat(w.getSqlSet()).contains("status=").contains("sent_at=").contains("error_message=");
        List<OffsetDateTime> times = timesOf(w);
        assertThat(times).hasSize(1); // 仅 sent_at，无 next_retry_at
        assertThat(times.get(0)).isCloseTo(OffsetDateTime.now(), within(5, ChronoUnit.SECONDS));

        ArgumentCaptor<LambdaUpdateWrapper<NfyaChannel>> chCap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(channelMapper).update(isNull(), chCap.capture());
        assertThat(chCap.getValue().getParamNameValuePairs().containsValue(0)).isTrue(); // fail_count 清零
        // ND-L5-01：投递成功即渠道可用性证据 → last_verify_at 同步回填（语义对齐 verify() 成功路径）
        assertThat(chCap.getValue().getSqlSet()).contains("last_verify_at=");
        List<OffsetDateTime> chTimes = timesOf(chCap.getValue());
        assertThat(chTimes).hasSize(1); // 仅 last_verify_at，无多余时间参数
        assertThat(chTimes.get(0)).isCloseTo(OffsetDateTime.now(), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void deliverOne_success_cas_miss_skips_channel_fail_count_reset() {
        newEngine(stubSender("DINGTALK", "ok", null));
        channelEnabled();
        when(deliveryMapper.update(isNull(), any())).thenReturn(0); // reaper 已回收 → CAS 落空

        engine.deliverOne(delivery(1L, 7L, "DINGTALK", null));

        verify(deliveryMapper).update(isNull(), any());
        verify(channelMapper, never()).update(isNull(), any()); // 陈旧 worker 不清零渠道计数
    }

    // ---- 失败：退避与重试/DEAD ----

    @Test
    void deliverOne_failure_first_retry_schedules_default_first_backoff_60s() {
        newEngine(stubSender("DINGTALK", null, "robot removed"));
        channelEnabled();

        engine.deliverOne(delivery(1L, 7L, "DINGTALK", null));

        // 渠道 fail_count 累加
        ArgumentCaptor<LambdaUpdateWrapper<NfyaChannel>> chCap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(channelMapper).update(isNull(), chCap.capture());
        assertThat(chCap.getValue().getSqlSet()).contains("fail_count = fail_count + 1");

        ArgumentCaptor<LambdaUpdateWrapper<NfyaDelivery>> cap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(deliveryMapper).update(isNull(), cap.capture());
        LambdaUpdateWrapper<NfyaDelivery> w = cap.getValue();
        assertThat(paramsOf(w).containsValue("FAILED")).isTrue();
        assertThat(paramsOf(w).containsValue(1)).isTrue(); // retry_count=1
        assertThat(paramsOf(w).containsValue("robot removed")).isTrue();
        List<OffsetDateTime> times = timesOf(w);
        assertThat(times).hasSize(1);
        assertThat(times.get(0)).isCloseTo(OffsetDateTime.now().plusSeconds(60),
                within(5, ChronoUnit.SECONDS)); // 默认退避表第 1 档 1min
    }

    @Test
    void deliverOne_failure_third_retry_uses_last_backoff_900s() {
        newEngine(stubSender("DINGTALK", null, "boom"));
        channelEnabled();

        engine.deliverOne(delivery(1L, 7L, "DINGTALK", 2)); // 第 3 次失败

        ArgumentCaptor<LambdaUpdateWrapper<NfyaDelivery>> cap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(deliveryMapper).update(isNull(), cap.capture());
        assertThat(paramsOf(cap.getValue()).containsValue(3)).isTrue();
        assertThat(timesOf(cap.getValue()).get(0)).isCloseTo(OffsetDateTime.now().plusSeconds(900),
                within(5, ChronoUnit.SECONDS)); // 第 3 档 15min
        assertThat(paramsOf(cap.getValue()).containsValue("DEAD")).isFalse();
    }

    @Test
    void deliverOne_fourth_failure_marks_dead_without_next_retry() {
        newEngine(stubSender("DINGTALK", null, "boom"));
        channelEnabled();

        engine.deliverOne(delivery(1L, 7L, "DINGTALK", 3)); // 已 3 次 → 第 4 次 DEAD

        ArgumentCaptor<LambdaUpdateWrapper<NfyaDelivery>> cap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(deliveryMapper).update(isNull(), cap.capture());
        LambdaUpdateWrapper<NfyaDelivery> w = cap.getValue();
        assertThat(paramsOf(w).containsValue("DEAD")).isTrue();
        assertThat(paramsOf(w).containsValue(4)).isTrue();
        assertThat(w.getSqlSet()).doesNotContain("next_retry_at"); // 终态不再排期
        assertThat(timesOf(w)).isEmpty();
    }

    @Test
    void deliverOne_custom_backoff_config_and_malformed_fallback() {
        newEngine(stubSender("DINGTALK", null, "boom"));
        channelEnabled();
        conf.setBackoffSeconds("1,2");
        engine.deliverOne(delivery(1L, 7L, "DINGTALK", null));
        conf.setBackoffSeconds("not-a-number");
        engine.deliverOne(delivery(2L, 7L, "DINGTALK", null));

        ArgumentCaptor<LambdaUpdateWrapper<NfyaDelivery>> cap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(deliveryMapper, times(2)).update(isNull(), cap.capture()); // 单次 verify 全量捕获
        assertThat(timesOf(cap.getAllValues().get(0)).get(0)).isCloseTo(OffsetDateTime.now().plusSeconds(1),
                within(5, ChronoUnit.SECONDS)); // 自定义第 1 档 1s
        assertThat(timesOf(cap.getAllValues().get(1)).get(0))
                .isCloseTo(OffsetDateTime.now().plusSeconds(60), within(5, ChronoUnit.SECONDS)); // 畸形 → 回退 {60,300,900}
    }

    // ---- 渠道缺失/停用/限速：归还 PENDING ----

    @Test
    void deliverOne_channel_missing_or_disabled_reverts_to_pending_with_60s_delay() {
        newEngine(stubSender("DINGTALK", "ok", null));
        when(channelMapper.selectById(7L)).thenReturn(null); // 领取后渠道被删

        engine.deliverOne(delivery(1L, 7L, "DINGTALK", null));

        ArgumentCaptor<LambdaUpdateWrapper<NfyaDelivery>> cap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(deliveryMapper).update(isNull(), cap.capture());
        assertThat(paramsOf(cap.getValue()).containsValue("PENDING")).isTrue();
        assertThat(timesOf(cap.getValue()).get(0)).isCloseTo(OffsetDateTime.now().plusSeconds(60),
                within(5, ChronoUnit.SECONDS));

        // 停用渠道同理
        when(channelMapper.selectById(7L)).thenReturn(channel("DISABLED", 0, "TENANT", null));
        engine.deliverOne(delivery(2L, 7L, "DINGTALK", null));
        verify(deliveryMapper, times(2)).update(isNull(), cap.capture());
        assertThat(paramsOf(cap.getAllValues().get(1)).containsValue("PENDING")).isTrue();
    }

    @Test
    void deliverOne_over_rate_limit_returns_row_without_loading_channel_or_sender() {
        newEngine(stubSender("DINGTALK", "ok", null));
        channelEnabled();
        conf.setRateLimitPerMinute(1); // 窗内配额 1

        engine.deliverOne(delivery(1L, 7L, "DINGTALK", null)); // 占额
        engine.deliverOne(delivery(2L, 7L, "DINGTALK", null)); // 超限 → 归还

        // 第 2 行在配额处短路：渠道只被加载 1 次，sender 只被调用 1 次
        verify(channelMapper, times(1)).selectById(7L);
        ArgumentCaptor<LambdaUpdateWrapper<NfyaDelivery>> cap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        // 第 1 行成功 CAS + 第 2 行归还 PENDING
        verify(deliveryMapper, times(2)).update(isNull(), cap.capture());
        assertThat(paramsOf(cap.getAllValues().get(1)).containsValue("PENDING")).isTrue();
    }

    @Test
    void deliverZero_limit_disables_rate_limiting_entirely() {
        newEngine(stubSender("DINGTALK", "ok", null));
        channelEnabled();
        conf.setRateLimitPerMinute(0); // 0=不限

        engine.deliverOne(delivery(1L, 7L, "DINGTALK", null));
        engine.deliverOne(delivery(2L, 7L, "DINGTALK", null));
        verify(channelMapper, times(2)).selectById(7L); // 两次都进渠道加载
    }

    @Test
    void deliverOne_unknown_channel_type_fails_via_missing_adapter_summary() {
        newEngine(); // 空链：无任何适配器
        channelEnabled();

        engine.deliverOne(delivery(1L, 7L, "SMS", null));

        ArgumentCaptor<LambdaUpdateWrapper<NfyaDelivery>> cap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(deliveryMapper).update(isNull(), cap.capture());
        assertThat(paramsOf(cap.getValue()).containsValue("无适配器: SMS")).isTrue(); // 走失败路径
        assertThat(paramsOf(cap.getValue()).containsValue("FAILED")).isTrue();
    }

    @Test
    void sender_chain_reverse_resolution_lets_custom_sender_override_builtin() {
        // 内建必败 / 自定义必成：若解析到内建则回写 FAILED+摘要，解析到自定义则为 SUCCESS
        // （成功 CAS 不落 sender 摘要，故以成败状态区分被选中的适配器）
        ChannelSender builtin = stubSender("DINGTALK", null, "builtin-hit");
        ChannelSender custom = stubSender("DINGTALK", "custom-hit", null);
        when(customSenders.iterator()).thenReturn(List.of(custom).iterator());
        engine = new DeliveryEngine(deliveryMapper, channelMapper, messageMapper, recipientMapper,
                claimService, props, List.of(builtin), customSenders);
        channelEnabled();

        engine.deliverOne(delivery(1L, 7L, "DINGTALK", null));

        ArgumentCaptor<LambdaUpdateWrapper<NfyaDelivery>> cap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(deliveryMapper).update(isNull(), cap.capture());
        assertThat(paramsOf(cap.getValue()).containsValue("SUCCESS")).isTrue(); // 自定义覆盖内建（反向解析）
        assertThat(paramsOf(cap.getValue()).containsValue("builtin-hit")).isFalse();
    }

    // ---- 熔断（fail_count ≥5 → DISABLED + 属主站内信） ----

    @Test
    void consecutive_failures_break_user_scope_channel_and_notify_owner_with_epoch_biz_no() {
        newEngine(stubSender("DINGTALK", null, "boom"));
        // 属主判定用首次加载的渠道（onSendFailure 透传 ch 的 scope/userid），fresh 重读只供 fail_count 阈值
        when(channelMapper.selectById(7L))
                .thenReturn(channel("ENABLED", 4, "USER", "u9"))   // 投递时加载
                .thenReturn(channel("ENABLED", 5, "USER", "u9"));  // 累加后 fresh = 5 → 触发熔断
        when(channelMapper.update(isNull(), any())).thenReturn(1, 1);
        when(messageMapper.insert(any(NfyaMessage.class))).thenAnswer(inv -> {
            inv.<NfyaMessage>getArgument(0).setId(99L);
            return 1;
        });

        engine.deliverOne(delivery(1L, 7L, "DINGTALK", null));

        // DISABLED 单语句 CAS：eq ENABLED + apply fail_count >= 5
        ArgumentCaptor<LambdaUpdateWrapper<NfyaChannel>> chCap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(channelMapper, times(2)).update(isNull(), chCap.capture());
        LambdaUpdateWrapper<NfyaChannel> cas = chCap.getAllValues().get(1);
        assertThat(paramsOf(cas).containsValue("DISABLED")).isTrue();
        assertThat(paramsOf(cas).containsValue(5)).isTrue(); // apply 占位参数
        assertThat(whereOf(cas)).contains("status =").contains("fail_count >=");

        // 属主站内信（绕过类型校验直插 + 熔断纪元 biz_no）
        ArgumentCaptor<NfyaMessage> msgCap = ArgumentCaptor.forClass(NfyaMessage.class);
        verify(messageMapper).insert(msgCap.capture());
        assertThat(msgCap.getValue().getId()).isEqualTo(99L);
        assertThat(msgCap.getValue().getBizNo()).startsWith("channel-break-7-"); // 纪元后缀防二次熔断撞 uk
        assertThat(msgCap.getValue().getTypeCode()).isEqualTo("CHANNEL_ALERT");
        assertThat(msgCap.getValue().getLevel()).isEqualTo("URGENT");
        assertThat(msgCap.getValue().getStatus()).isEqualTo("SENT");
        verify(recipientMapper).insert(org.mockito.ArgumentMatchers.argThat((NfyaMessageRecipient r) ->
                "u9".equals(r.getUserid()) && "UNREAD".equals(r.getReadStatus())
                        && Long.valueOf(99L).equals(r.getMessageId())));
    }

    @Test
    void breaker_cas_miss_or_below_threshold_or_tenant_scope_skips_owner_inbox() {
        newEngine(stubSender("DINGTALK", null, "boom"));

        // ① fresh fail_count=4 < 5：连 CAS 都不发
        when(channelMapper.selectById(7L)).thenReturn(channel("ENABLED", 4, "TENANT", null));
        engine.deliverOne(delivery(1L, 7L, "DINGTALK", null));
        verify(channelMapper, times(1)).update(isNull(), any()); // 仅失败计数累加

        // ② CAS 落空（已被并发熔断/验证清零）：不插站内信
        when(channelMapper.selectById(7L))
                .thenReturn(channel("ENABLED", 4, "TENANT", null))
                .thenReturn(channel("ENABLED", 5, "USER", "u9"));
        when(channelMapper.update(isNull(), any())).thenReturn(1, 0);
        engine.deliverOne(delivery(2L, 7L, "DINGTALK", null));

        // ③ USER scope 但 userid 为空：熔断成功也不插站内信
        when(channelMapper.selectById(7L))
                .thenReturn(channel("ENABLED", 4, "USER", "  "))
                .thenReturn(channel("ENABLED", 5, "USER", "  "));
        when(channelMapper.update(isNull(), any())).thenReturn(1, 1);
        engine.deliverOne(delivery(3L, 7L, "DINGTALK", null));

        verify(messageMapper, never()).insert(any(NfyaMessage.class));
        verify(recipientMapper, never()).insert(any(NfyaMessageRecipient.class)); // never() 走 any() 单侧也可
    }

    // ---- Reaper：SENDING 超时回收 ----

    @Test
    void reapOnce_recycles_stale_sending_rows_to_pending() {
        newEngine();
        EngineMockSupport.setRunning(engine, true);
        NfyaDelivery s1 = delivery(11L, 7L, "DINGTALK", null);
        NfyaDelivery s2 = delivery(22L, 7L, "WECOM", null);
        when(deliveryMapper.findStaleSending(any(OffsetDateTime.class), any(int.class)))
                .thenReturn(List.of(s1, s2));

        engine.reapOnce();

        ArgumentCaptor<LambdaUpdateWrapper<NfyaDelivery>> cap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(deliveryMapper, times(2)).update(isNull(), cap.capture());
        assertThat(paramsOf(cap.getAllValues().get(0)).containsValue("PENDING")).isTrue();
        assertThat(whereOf(cap.getAllValues().get(0))).contains("id =").contains("status =");
        assertThat(paramsOf(cap.getAllValues().get(1)).containsValue(22L)).isTrue();
        // staleBefore = now - sendingStaleMs：非未来时间
        ArgumentCaptor<OffsetDateTime> beforeCap = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(deliveryMapper).findStaleSending(beforeCap.capture(), any(int.class));
        assertThat(beforeCap.getValue()).isBeforeOrEqualTo(OffsetDateTime.now());
    }

    @Test
    void reapOnce_mapper_exception_is_swallowed_and_running_guard_blocks_scan() {
        newEngine();
        EngineMockSupport.setRunning(engine, true);
        when(deliveryMapper.findStaleSending(any(OffsetDateTime.class), any(int.class)))
                .thenThrow(new RuntimeException("db down"));
        assertThatNoException().isThrownBy(engine::reapOnce); // reaper 异常吞掉，不杀调度线程

        EngineMockSupport.setRunning(engine, false);
        engine.scanOnce();
        verifyNoInteractions(claimService); // running=false → 扫描守卫直接返回
    }

    // ---- scanOnce：领取 → 并行投递 ----

    @Test
    void scanOnce_claims_batch_and_dispatches_delivery_to_worker_pool() {
        newEngine(stubSender("DINGTALK", "ok", null));
        channelEnabled();
        EngineMockSupport.setRunning(engine, true);
        when(claimService.claim(conf.getBatchSize())).thenReturn(List.of(delivery(1L, 7L, "DINGTALK", null)));
        when(deliveryMapper.update(isNull(), any())).thenReturn(1);

        engine.scanOnce();

        verify(claimService).claim(conf.getBatchSize());
        verify(deliveryMapper, timeout(2000)).update(isNull(), any()); // worker 异步完成 SUCCESS 回写
    }

    @Test
    void scanOnce_empty_claim_makes_no_dispatch_and_worker_exception_reverts() {
        newEngine(stubSender("DINGTALK", "ok", null));
        channelEnabled();
        EngineMockSupport.setRunning(engine, true);
        when(claimService.claim(conf.getBatchSize())).thenReturn(List.of());

        engine.scanOnce();
        verify(deliveryMapper, never()).update(isNull(), any()); // 空批零分发

        // worker 内异常（渠道加载炸）→ 归还 PENDING，不丢行
        when(claimService.claim(conf.getBatchSize())).thenReturn(List.of(delivery(9L, 7L, "DINGTALK", null)));
        when(channelMapper.selectById(7L)).thenThrow(new RuntimeException("db down"));
        engine.scanOnce();
        ArgumentCaptor<LambdaUpdateWrapper<NfyaDelivery>> cap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(deliveryMapper, timeout(2000)).update(isNull(), cap.capture());
        assertThat(paramsOf(cap.getValue()).containsValue("PENDING")).isTrue();
    }

    // ---- 生命周期（调度线程正确性登记为 IT 覆盖，这里只验证开关与关闭安全） ----

    @Test
    void lifecycle_disabled_engine_stays_idle_and_stop_is_safe() {
        newEngine();
        conf.setEnabled(false);
        engine.start();
        assertThat(engine.isRunning()).isFalse(); // 默认关：start 不启动调度
        engine.stop(); // 未 start 也安全
        assertThat(engine.isRunning()).isFalse();
    }

    @Test
    void lifecycle_enabled_engine_runs_then_stops_cleanly() {
        newEngine();
        conf.setEnabled(true);
        engine.start();
        assertThat(engine.isRunning()).isTrue();
        engine.stop();
        assertThat(engine.isRunning()).isFalse(); // stop 置位 + 关闭调度/worker 不挂起
    }
}
