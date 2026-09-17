package fun.commons.notification4j.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.notification4j.entity.NfyaAnnouncement;
import fun.commons.notification4j.entity.NfyaChannel;
import fun.commons.notification4j.entity.NfyaDelivery;
import fun.commons.notification4j.entity.NfyaMessageType;
import fun.commons.notification4j.entity.NfyaSubscription;
import fun.commons.notification4j.mapper.NfyaChannelMapper;
import fun.commons.notification4j.mapper.NfyaDeliveryMapper;
import fun.commons.notification4j.mapper.NfyaMessageTypeMapper;
import fun.commons.notification4j.mapper.NfyaSubscriptionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 25 步 service 层 mock 单测：DeliveryPlanService（send/发布时订阅矩阵展开 → nfya_delivery）。
 * mock 五 mapper。分支：URGENT 全量渠道（免打扰豁免）/订阅行实例过滤/无订阅行走类型 default_channels
 * 语义/类型缺失零计划/uk 幂等跳过/公告公共渠道过滤（跨租户/禁用/非数字 id 跳过）/
 * 订阅 ANNOUNCEMENT 展开/quiet 窗接线（insertRows 经反射以固定 quietEnd 钉值断言 next_retry_at）。
 */
// VECTOR: TAG=step25-unit
class DeliveryPlanServicePlanTest {

    private final NfyaChannelMapper channelMapper = mock(NfyaChannelMapper.class);
    private final NfyaSubscriptionMapper subscriptionMapper = mock(NfyaSubscriptionMapper.class);
    private final NfyaMessageTypeMapper messageTypeMapper = mock(NfyaMessageTypeMapper.class);
    private final NfyaDeliveryMapper deliveryMapper = mock(NfyaDeliveryMapper.class);
    private final DeliveryPlanService service =
            new DeliveryPlanService(channelMapper, subscriptionMapper, messageTypeMapper, deliveryMapper,
                    new ObjectMapper());

    private static final Method INSERT_ROWS = insertRows();

    private static Method insertRows() {
        try {
            Method m = DeliveryPlanService.class.getDeclaredMethod("insertRows", long.class, String.class,
                    Long.class, String.class, String.class, List.class, OffsetDateTime.class);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void insertsSucceed() {
        when(deliveryMapper.insert(any(NfyaDelivery.class))).thenAnswer(inv -> {
            inv.<NfyaDelivery>getArgument(0).setId(1L);
            return 1;
        });
    }

    private static NfyaChannel channel(long id, String type) {
        NfyaChannel ch = new NfyaChannel();
        ch.setId(id);
        ch.setTenantId(1L);
        ch.setUserid("u1");
        ch.setScope("USER");
        ch.setChannelType(type);
        ch.setTarget("justin@example.com");
        ch.setStatus("ENABLED");
        return ch;
    }

    private static NfyaMessageType type(String defaultChannels) {
        NfyaMessageType t = new NfyaMessageType();
        t.setTenantId(1L);
        t.setTypeCode("OTC");
        t.setDefaultChannels(defaultChannels);
        return t;
    }

    private static NfyaSubscription sub(String userid, String channelIds, String quiet) {
        NfyaSubscription s = new NfyaSubscription();
        s.setTenantId(1L);
        s.setUserid(userid);
        s.setTypeCode("OTC");
        s.setChannelIds(channelIds);
        s.setQuietHours(quiet);
        return s;
    }

    // ---- planForMessage ----

    @Test
    void plan_message_urgent_uses_all_enabled_channels_ignoring_matrix() {
        when(messageTypeMapper.selectOne(any())).thenReturn(type("[\"INAPP\"]"));
        when(channelMapper.selectList(any())).thenReturn(List.of(channel(1L, "EMAIL"), channel(2L, "DINGTALK")));
        insertsSucceed();

        int planned = service.planForMessage(1L, 9L, "OTC", "URGENT", "标题", List.of("u1"));
        assertThat(planned).isEqualTo(2); // URGENT 无视订阅矩阵勾选
        verify(subscriptionMapper, org.mockito.Mockito.never()).selectOne(any());
    }

    @Test
    void plan_message_subscription_row_keeps_only_chosen_enabled_instance() {
        when(messageTypeMapper.selectOne(any())).thenReturn(type("[\"INAPP\"]"));
        when(channelMapper.selectList(any())).thenReturn(List.of(channel(1L, "EMAIL"), channel(2L, "DINGTALK")));
        when(subscriptionMapper.selectOne(any())).thenReturn(sub("u1", "[\"2\"]", "{}"));
        insertsSucceed();

        int planned = service.planForMessage(1L, 9L, "OTC", "NORMAL", "标题", List.of("u1"));
        assertThat(planned).isEqualTo(1);
        ArgumentCaptor<NfyaDelivery> cap = ArgumentCaptor.forClass(NfyaDelivery.class);
        verify(deliveryMapper).insert(cap.capture());
        assertThat(cap.getValue()).extracting(NfyaDelivery::getSourceType, NfyaDelivery::getSourceId,
                        NfyaDelivery::getUserid, NfyaDelivery::getChannelId, NfyaDelivery::getChannelType)
                .containsExactly("MESSAGE", 9L, "u1", 2L, "DINGTALK");
        // 订阅勾选的是 2 号 DINGTALK 渠道：无 query 串 → 脱敏原样返回（EMAIL 实例才遮蔽本地段）
        assertThat(cap.getValue().getTarget()).isEqualTo("justin@example.com");
        assertThat(cap.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(cap.getValue().getNextRetryAt()).isNotNull();
        assertThat(cap.getValue().getTitle()).isEqualTo("标题");
    }

    @Test
    void plan_message_without_subscription_uses_type_default_channels() {
        when(messageTypeMapper.selectOne(any())).thenReturn(type("[\"EMAIL\"]"));
        when(channelMapper.selectList(any())).thenReturn(List.of(channel(1L, "EMAIL"), channel(2L, "DINGTALK")));
        when(subscriptionMapper.selectOne(any())).thenReturn(null);
        insertsSucceed();

        int planned = service.planForMessage(1L, 9L, "OTC", "NORMAL", "标题", List.of("u1"));
        assertThat(planned).isEqualTo(1); // 类型语义 → 仅 EMAIL 实例
        ArgumentCaptor<NfyaDelivery> cap = ArgumentCaptor.forClass(NfyaDelivery.class);
        verify(deliveryMapper).insert(cap.capture());
        assertThat(cap.getValue().getChannelType()).isEqualTo("EMAIL");
    }

    @Test
    void plan_message_unknown_type_and_no_subscription_plans_nothing() {
        when(messageTypeMapper.selectOne(any())).thenReturn(null);
        assertThat(service.planForMessage(1L, 9L, "GHOST", "NORMAL", "t", List.of("u1"))).isZero();
        verify(deliveryMapper, org.mockito.Mockito.never()).insert(any(NfyaDelivery.class));
    }

    @Test
    void plan_message_duplicate_delivery_rows_are_silently_skipped() {
        when(messageTypeMapper.selectOne(any())).thenReturn(type("[\"INAPP\"]"));
        when(channelMapper.selectList(any())).thenReturn(List.of(channel(1L, "EMAIL"), channel(2L, "DINGTALK")));
        when(deliveryMapper.insert(any(NfyaDelivery.class)))
                .thenThrow(new DuplicateKeyException("uk")) // 重放幂等：静默跳过
                .thenAnswer(inv -> {
                    inv.<NfyaDelivery>getArgument(0).setId(2L);
                    return 1;
                });
        int planned = service.planForMessage(1L, 9L, "OTC", "URGENT", "标题", List.of("u1"));
        assertThat(planned).isEqualTo(1);
    }

    @Test
    void plan_message_with_dirty_quiet_fails_open_to_immediate() {
        when(messageTypeMapper.selectOne(any())).thenReturn(type("[\"INAPP\"]"));
        when(channelMapper.selectList(any())).thenReturn(List.of(channel(1L, "EMAIL")));
        when(subscriptionMapper.selectOne(any())).thenReturn(sub("u1", "[\"1\"]", "not-json{")); // 脏 jsonb
        insertsSucceed();
        service.planForMessage(1L, 9L, "OTC", "NORMAL", "t", List.of("u1"));
        ArgumentCaptor<NfyaDelivery> cap = ArgumentCaptor.forClass(NfyaDelivery.class);
        verify(deliveryMapper).insert(cap.capture());
        assertThat(cap.getValue().getNextRetryAt()).isNotNull(); // fail-open 即时可扫
        assertThat(cap.getValue().getRetryCount()).isZero();
        assertThat(cap.getValue().getErrorMessage()).isEmpty();
        assertThat(cap.getValue().getExt()).isEqualTo("{}");
    }

    @Test
    void plan_message_multi_user_expands_per_user() {
        when(messageTypeMapper.selectOne(any())).thenReturn(type("[\"INAPP\"]"));
        when(channelMapper.selectList(any())).thenReturn(List.of(channel(1L, "EMAIL")));
        when(subscriptionMapper.selectOne(any())).thenReturn(sub("u2", "[\"1\"]", "{}"));
        insertsSucceed();
        int planned = service.planForMessage(1L, 9L, "OTC", "NORMAL", "t", List.of("u1", "u2"));
        assertThat(planned).isEqualTo(2);
        verify(deliveryMapper, org.mockito.Mockito.times(2)).insert(any(NfyaDelivery.class));
    }

    // ---- planForAnnouncement ----

    private static NfyaAnnouncement announcement(String channelIds) {
        NfyaAnnouncement a = new NfyaAnnouncement();
        a.setId(50L);
        a.setTenantId(1L);
        a.setTitle("公告");
        a.setChannelIds(channelIds);
        return a;
    }

    private static NfyaChannel publicChannel(long id, long tenantId, String scope, String status) {
        NfyaChannel ch = new NfyaChannel();
        ch.setId(id);
        ch.setTenantId(tenantId);
        ch.setScope(scope);
        ch.setChannelType("DINGTALK");
        ch.setTarget("https://oapi.dingtalk.com/robot/send?access_token=x");
        ch.setStatus(status);
        return ch;
    }

    @Test
    void plan_announcement_keeps_only_own_tenant_enabled_public_channels() {
        when(channelMapper.selectById(1L)).thenReturn(publicChannel(1L, 1L, "TENANT", "ENABLED"));
        when(channelMapper.selectById(2L)).thenReturn(publicChannel(2L, 9L, "TENANT", "ENABLED")); // 跨租户
        when(channelMapper.selectById(3L)).thenReturn(publicChannel(3L, 1L, "TENANT", "DISABLED")); // 非启用
        when(subscriptionMapper.selectList(any())).thenReturn(List.of());
        insertsSucceed();

        int planned = service.planForAnnouncement(announcement("[\"1\",\"2\",\"3\",\"oops\"]"));
        assertThat(planned).isEqualTo(1); // 非数字 id "oops" 静默跳过
        ArgumentCaptor<NfyaDelivery> cap = ArgumentCaptor.forClass(NfyaDelivery.class);
        verify(deliveryMapper).insert(cap.capture());
        assertThat(cap.getValue()).extracting(NfyaDelivery::getSourceType, NfyaDelivery::getUserid)
                .containsExactly("ANNOUNCEMENT", "");
        assertThat(cap.getValue().getTarget()).isEqualTo("https://oapi.dingtalk.com/robot/send?****");
    }

    @Test
    void plan_announcement_missing_public_channel_is_skipped() {
        when(channelMapper.selectById(1L)).thenReturn(null);
        when(subscriptionMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.planForAnnouncement(announcement("[\"1\"]"))).isZero();
    }

    @Test
    void plan_announcement_expands_announcement_subscribers() {
        when(channelMapper.selectById(1L)).thenReturn(null); // 公共渠道无
        when(channelMapper.selectList(any())).thenReturn(List.of(channel(7L, "EMAIL"), channel(8L, "DINGTALK")));
        when(subscriptionMapper.selectList(any())).thenReturn(List.of(sub("u1", "[\"7\",\"99\"]", "{}")));
        insertsSucceed();

        int planned = service.planForAnnouncement(announcement("[]"));
        assertThat(planned).isEqualTo(1); // 勾选的 7 在 ENABLED 内；99 不存在被过滤
        verify(subscriptionMapper).selectList(any()); // 按 ANNOUNCEMENT 类型查订阅
    }

    @Test
    void plan_announcement_subscriber_dirty_quiet_fails_open() {
        when(channelMapper.selectById(1L)).thenReturn(null);
        when(channelMapper.selectList(any())).thenReturn(List.of(channel(7L, "EMAIL")));
        when(subscriptionMapper.selectList(any()))
                .thenReturn(List.of(sub("u1", "[\"7\"]", "{\"start\":\"bad\"}")));
        insertsSucceed();
        service.planForAnnouncement(announcement("[]"));
        ArgumentCaptor<NfyaDelivery> cap = ArgumentCaptor.forClass(NfyaDelivery.class);
        verify(deliveryMapper).insert(cap.capture());
        assertThat(cap.getValue().getUserid()).isEqualTo("u1");
        assertThat(cap.getValue().getNextRetryAt()).isNotNull();
    }

    @Test
    void plan_announcement_null_channel_ids_yields_no_public_rows() {
        when(subscriptionMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.planForAnnouncement(announcement(null))).isZero();
    }

    // ---- insertRows 接线（quietEnd 非 null 传入 → next_retry_at=窗结束时刻）----

    @Test
    void insert_rows_pins_next_retry_at_to_quiet_end_when_deferred() {
        insertsSucceed();
        OffsetDateTime quietEnd = LocalDateTime.of(2026, 6, 15, 8, 0)
                .atZone(ZoneId.systemDefault()).toOffsetDateTime();
        try {
            int inserted = (int) INSERT_ROWS.invoke(service, 1L, "MESSAGE", 9L, "u1", "标题",
                    List.of(channel(1L, "EMAIL"), channel(2L, "DINGTALK")), quietEnd);
            assertThat(inserted).isEqualTo(2);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        ArgumentCaptor<NfyaDelivery> cap = ArgumentCaptor.forClass(NfyaDelivery.class);
        verify(deliveryMapper, org.mockito.Mockito.times(2)).insert(cap.capture());
        assertThat(cap.getAllValues())
                .allSatisfy(d -> assertThat(d.getNextRetryAt()).isEqualTo(quietEnd)); // 推迟发送非丢弃
        assertThat(cap.getAllValues()).allSatisfy(d -> assertThat(d.getStatus()).isEqualTo("PENDING"));
    }

    @Test
    void insert_rows_null_title_defaults_to_empty_string() {
        insertsSucceed();
        try {
            INSERT_ROWS.invoke(service, 1L, "MESSAGE", 9L, "u1", null,
                    List.of(channel(1L, "EMAIL")), null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        ArgumentCaptor<NfyaDelivery> cap = ArgumentCaptor.forClass(NfyaDelivery.class);
        verify(deliveryMapper).insert(cap.capture());
        assertThat(cap.getValue().getTitle()).isEmpty();
        assertThat(cap.getValue().getNextRetryAt()).isNotNull(); // quietEnd=null → now（即时可扫）
    }
}
