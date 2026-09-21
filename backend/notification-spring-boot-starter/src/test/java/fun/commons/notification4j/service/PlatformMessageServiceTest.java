package fun.commons.notification4j.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import fun.commons.framework4j.web.ApiException;
import fun.commons.notification4j.entity.NfyaMessage;
import fun.commons.notification4j.mapper.NfyaMessageMapper;
import fun.commons.notification4j.mapper.NfyaMessageRecipientMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 平台维度消息查询单测：PlatformMessageService（API-PPM-001/002）。
 * 依赖 = 既有 MessageService（反射注入 baseMapper，同 MessageServiceApiTest 口径）+ 收件人 mapper；
 * 覆盖：列表筛选 SQL 段（user_id 子查询/type_code/时间窗/keyword）+ 分页 LIMIT/OFFSET 尾缀 +
 * total 独立 count（wrapper 不可复用 last 尾缀）+ 详情全字段/已读统计 + 防探测 10400（非数字/不存在）。
 */
// VECTOR: TAG=platform-message-unit
class PlatformMessageServiceTest {

    private final NfyaMessageMapper messageMapper = mock(NfyaMessageMapper.class);
    private final NfyaMessageRecipientMapper recipientMapper = mock(NfyaMessageRecipientMapper.class);
    private final MessageService messageService = new MessageService(
            mock(fun.commons.notification4j.mapper.NfyaMessageRecipientMapper.class),
            new MessageTypeService(new com.fasterxml.jackson.databind.ObjectMapper()),
            mock(DeliveryPlanService.class),
            mock(fun.commons.notification4j.mapper.NfyaDeliveryMapper.class));

    private PlatformMessageService service;

    @BeforeAll
    static void initTableInfos() {
        ServiceMockSupport.initTableInfo(NfyaMessage.class,
                fun.commons.notification4j.entity.NfyaMessageRecipient.class);
    }

    @BeforeEach
    void setUp() {
        ServiceMockSupport.injectBaseMapper(messageService, messageMapper);
        service = new PlatformMessageService(messageService, recipientMapper);
    }

    @Test
    void list_returns_offset_page_with_total() {
        NfyaMessage m = message(11L, 1L, "WORK_FAILED", "作品生成失败");
        when(messageMapper.selectList(any())).thenReturn(List.of(m));
        when(messageMapper.selectCount(any())).thenReturn(1L);

        Map<String, Object> data = service.list(null, null, null, null, null, 0, 20);

        assertThat(data.get("total")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("list");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("message_id")).isEqualTo("11");
        assertThat(items.get(0).get("tenant_id")).isEqualTo(1L);
        assertThat(items.get(0).get("type_code")).isEqualTo("WORK_FAILED");
        assertThat(items.get(0).get("created_at")).isEqualTo(m.getCreatedAt().toInstant().toEpochMilli());
    }

    @Test
    void list_applies_all_filters_and_pagination_suffix() {
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(messageMapper.selectCount(any())).thenReturn(0L);

        service.list("u-1", "ADMIN_ANNOUNCE", 1780000000000L, 1790000000000L, "公告", 40, 50);

        org.mockito.ArgumentCaptor<Wrapper> listCap =
                org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        org.mockito.ArgumentCaptor<Wrapper> countCap =
                org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        org.mockito.Mockito.verify(messageMapper).selectList(listCap.capture());
        org.mockito.Mockito.verify(messageMapper).selectCount(countCap.capture());
        String listSql = listCap.getValue().getSqlSegment();
        String countSql = countCap.getValue().getSqlSegment();
        // 筛选段：user_id 收件人子查询 + type_code + 时间窗 + keyword 括号组
        assertThat(listSql).contains("id IN (SELECT message_id FROM nfya_message_recipient WHERE userid =");
        assertThat(listSql).contains("type_code =");
        assertThat(listSql).contains("created_at >=");
        assertThat(listSql).contains("created_at <=");
        assertThat(listSql).contains("title LIKE");
        assertThat(listSql).contains("content LIKE");
        // 分页尾缀拼在 getSqlSegment 尾部，且只落在列表查询（count 无 LIMIT/OFFSET）
        assertThat(listSql).contains("LIMIT 50 OFFSET 40");
        assertThat(countSql).doesNotContain("LIMIT");
        // count 与 list 各建 wrapper：筛选段一致
        assertThat(countSql).contains("type_code =").contains("created_at >=");
    }

    @Test
    void detail_returns_full_fields_with_read_count() {
        NfyaMessage m = message(42L, 3L, "WORK_DONE", "任务完成");
        m.setBizNo("WORK_DONE:biz-1");
        m.setContent("正文内容");
        m.setLinkUrl("https://x/y");
        m.setSender("API");
        when(messageMapper.selectById(42L)).thenReturn(m);
        when(recipientMapper.selectCount(any())).thenReturn(2L);

        Map<String, Object> data = service.detail("42");

        assertThat(data.get("message_id")).isEqualTo("42");
        assertThat(data.get("biz_no")).isEqualTo("WORK_DONE:biz-1");
        assertThat(data.get("content")).isEqualTo("正文内容");
        assertThat(data.get("link_url")).isEqualTo("https://x/y");
        assertThat(data.get("sender")).isEqualTo("API");
        assertThat(data.get("read_count")).isEqualTo(2L);
    }

    @Test
    void detail_rejects_non_numeric_id_with_10400() {
        assertThatThrownBy(() -> service.detail("abc"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("资源不存在或无权访问")
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(10400);
    }

    @Test
    void detail_rejects_missing_message_with_10400() {
        when(messageMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.detail("99"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(10400);
    }

    private NfyaMessage message(long id, long tenantId, String typeCode, String title) {
        NfyaMessage m = new NfyaMessage();
        m.setId(id);
        m.setTenantId(tenantId);
        m.setTypeCode(typeCode);
        m.setLevel("NORMAL");
        m.setTitle(title);
        m.setStatus("SENT");
        m.setReceiverCount(1);
        m.setCreatedAt(OffsetDateTime.parse("2026-09-01T10:00:00+00:00"));
        return m;
    }
}
