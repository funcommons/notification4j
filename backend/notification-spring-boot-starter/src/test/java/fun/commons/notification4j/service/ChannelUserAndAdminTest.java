package fun.commons.notification4j.service;

import fun.commons.notification4j.dto.PatchChannelsChannelIdRequest;
import fun.commons.notification4j.dto.PostChannelsRequest;
import fun.commons.notification4j.entity.NfyaChannel;
import fun.commons.notification4j.mapper.NfyaChannelMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 25 步 service 层 mock 单测：ChannelService（USER 面）与 ChannelAdminService（TENANT 面）
 * 对 ChannelCoreService 的委托接线 + 自有编排（列表脱敏映射/删除级联订阅剔除）。
 * core/subscriptionService 全 mock；ChannelService 自身 ServiceImpl#baseMapper 反射注入。
 */
// VECTOR: TAG=step25-unit
class ChannelUserAndAdminTest {

    private final ChannelCoreService core = mock(ChannelCoreService.class);
    private final SubscriptionService subscriptionService = mock(SubscriptionService.class);
    private final NfyaChannelMapper channelMapper = mock(NfyaChannelMapper.class);

    private ChannelService newChannelService() {
        ChannelService service = ServiceMockSupport.injectBaseMapper(
                new ChannelService(core, subscriptionService), channelMapper);
        // lambdaQuery() 链式入口需 mapperClass/entityClass 缓存（纯 mock mapper 非 MybatisMapperProxy）
        ServiceMockSupport.primeServiceImplMetadata(service, NfyaChannelMapper.class, NfyaChannel.class);
        return service;
    }

    private static NfyaChannel channel(long id, String type, String target, String status) {
        NfyaChannel ch = new NfyaChannel();
        ch.setId(id);
        ch.setTenantId(1L);
        ch.setChannelType(type);
        ch.setTarget(target);
        ch.setName("渠道" + id);
        ch.setStatus(status);
        ch.setFailCount(1);
        ch.setLastVerifyAt(OffsetDateTime.parse("2026-06-01T00:00Z"));
        return ch;
    }

    // ---- ChannelService（USER 面）----

    @Test
    void user_register_delegates_with_user_scope() {
        PostChannelsRequest req = new PostChannelsRequest("EMAIL", "邮箱", "a@x.com", null, null);
        Map<String, Object> out = Map.of("channel_id", "1");
        when(core.register(1L, "u1", ChannelCoreService.SCOPE_USER, req)).thenReturn(out);
        assertThat(newChannelService().register(1L, "u1", req)).isSameAs(out);
    }

    @Test
    void user_verify_and_patch_delegate_with_user_scope_and_user_face_message() {
        newChannelService().verify(1L, "u1", "9");
        verify(core).verify(1L, "u1", ChannelCoreService.SCOPE_USER, "9", "资源不存在或无权访问");

        PatchChannelsChannelIdRequest req = new PatchChannelsChannelIdRequest("新名", null);
        newChannelService().patch(1L, "u1", "9", req);
        verify(core).patch(1L, "u1", ChannelCoreService.SCOPE_USER, "9", req, "资源不存在或无权访问");
    }

    @Test
    void user_delete_cascades_subscription_removal() {
        NfyaChannel ch = channel(9L, "DINGTALK", "https://oapi/x", "ENABLED");
        when(core.requireOwned(1L, "u1", ChannelCoreService.SCOPE_USER, "9", "资源不存在或无权访问")).thenReturn(ch);
        when(channelMapper.deleteById(9L)).thenReturn(1);

        var data = newChannelService().delete(1L, "u1", "9");

        assertThat(data).isEqualTo(Map.of("channel_id", "9"));
        verify(channelMapper).deleteById(9L);
        verify(subscriptionService).removeChannel(1L, "u1", "9");
    }

    @Test
    void user_list_masks_targets_and_maps_fields() {
        when(channelMapper.selectList(any())).thenReturn(List.of(
                channel(1L, "EMAIL", "justin@example.com", "ENABLED"),
                channel(2L, "DINGTALK", "https://oapi.dingtalk.com/robot/send?access_token=abc", "DISABLED")));

        var data = newChannelService().list(1L, "u1");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertThat(list).hasSize(2);
        assertThat(list.get(0)).containsEntry("channel_id", "1").containsEntry("target", "j***@example.com")
                .containsEntry("status", "ENABLED").containsEntry("fail_count", 1);
        assertThat(list.get(0).get("last_verify_at")).isEqualTo(OffsetDateTime.parse("2026-06-01T00:00Z").toInstant().toEpochMilli());
        assertThat(list.get(1)).containsEntry("target", "https://oapi.dingtalk.com/robot/send?****")
                .containsEntry("status", "DISABLED");
    }

    @Test
    void user_list_masks_null_last_verify_as_null() {
        NfyaChannel ch = channel(3L, "WECOM", "https://qyapi.weixin.qq.com/cgi-bin/x", "PENDING");
        ch.setLastVerifyAt(null);
        when(channelMapper.selectList(any())).thenReturn(List.of(ch));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) newChannelService().list(1L, "u1").get("list");
        assertThat(list.get(0).get("last_verify_at")).isNull();
    }

    // ---- ChannelAdminService（TENANT 面）----

    @Test
    void admin_register_delegates_with_tenant_scope_and_empty_userid() {
        ChannelAdminService admin = new ChannelAdminService(channelMapper, core);
        PostChannelsRequest req = new PostChannelsRequest("FEISHU", "公共渠道", "https://feishu.cn/hook/t", null, null);
        admin.register(1L, req);
        verify(core).register(1L, "", ChannelCoreService.SCOPE_TENANT, req);
    }

    @Test
    void admin_verify_and_patch_delegate_with_admin_face_message() {
        ChannelAdminService admin = new ChannelAdminService(channelMapper, core);
        admin.verify(1L, "9");
        verify(core).verify(1L, null, ChannelCoreService.SCOPE_TENANT, "9", "渠道不存在");

        PatchChannelsChannelIdRequest req = new PatchChannelsChannelIdRequest(null, "DISABLED");
        admin.patch(1L, "9", req);
        verify(core).patch(1L, null, ChannelCoreService.SCOPE_TENANT, "9", req, "渠道不存在");
    }

    @Test
    void admin_delete_uses_direct_mapper_delete_without_subscription_cascade() {
        ChannelAdminService admin = new ChannelAdminService(channelMapper, core);
        when(channelMapper.deleteById(9L)).thenReturn(1);
        var data = admin.delete(1L, "9");
        verify(core).requireOwned(1L, null, ChannelCoreService.SCOPE_TENANT, "9", "渠道不存在");
        verify(subscriptionService, never()).removeChannel(anyLong(), anyString(), anyString());
        assertThat(data).isEqualTo(Map.of("channel_id", "9"));
    }

    @Test
    void admin_list_uses_tenant_scope_masking() {
        ChannelAdminService admin = new ChannelAdminService(channelMapper, core);
        when(channelMapper.selectList(any())).thenReturn(List.of(
                channel(7L, "EMAIL", "ops@example.com", "ENABLED")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) admin.list(1L).get("list");
        assertThat(list).hasSize(1);
        assertThat(list.get(0)).containsEntry("channel_id", "7").containsEntry("target", "o***@example.com");
        assertThat(list.get(0)).doesNotContainKey("userid"); // 公共渠道无 userid 维度
    }

    @Test
    void admin_list_empty_tenant_returns_empty_list() {
        ChannelAdminService admin = new ChannelAdminService(channelMapper, core);
        when(channelMapper.selectList(any())).thenReturn(List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) admin.list(1L).get("list");
        assertThat(list).isEmpty();
    }
}
