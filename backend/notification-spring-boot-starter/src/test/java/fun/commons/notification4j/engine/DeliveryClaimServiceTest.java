package fun.commons.notification4j.engine;

import com.baomidou.mybatisplus.core.conditions.ISqlSegment;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import fun.commons.notification4j.entity.NfyaDelivery;
import fun.commons.notification4j.mapper.NfyaDeliveryMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 26 步 engine 层单测：DeliveryClaimService SKIP LOCKED 批次领取映射。
 * claimBatch(now, batch) 时间参数为当下 / 返回行 id 全量置 SENDING（单语句批量，非 N+1）/
 * 空批零回写 / 返回原行列表（引擎按行并行投递）。@Transactional 由代理层承担，纯 mock 下不验证。
 */
// VECTOR: TAG=step26-unit
class DeliveryClaimServiceTest {

    private final NfyaDeliveryMapper deliveryMapper = mock(NfyaDeliveryMapper.class);
    private final DeliveryClaimService service = new DeliveryClaimService(deliveryMapper);

    @BeforeAll
    static void initTableInfo() {
        EngineMockSupport.initTableInfo(NfyaDelivery.class);
    }

    private static NfyaDelivery row(long id) {
        NfyaDelivery d = new NfyaDelivery();
        d.setId(id);
        d.setStatus("PENDING");
        return d;
    }

    @Test
    void claim_maps_claimed_rows_ids_into_single_batched_sending_update() {
        when(deliveryMapper.claimBatch(any(OffsetDateTime.class), any(int.class)))
                .thenReturn(List.of(row(1L), row(2L), row(3L)));

        List<NfyaDelivery> claimed = service.claim(25);

        // claimBatch 时间参数 = 当下（next_retry_at <= now 语义由 SQL 承担）
        ArgumentCaptor<OffsetDateTime> nowCap = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(deliveryMapper).claimBatch(nowCap.capture(), org.mockito.ArgumentMatchers.eq(25));
        assertThat(nowCap.getValue()).isCloseTo(OffsetDateTime.now(), within(10, ChronoUnit.SECONDS));

        // 单语句批量置 SENDING：in(ids) + set status
        ArgumentCaptor<LambdaUpdateWrapper<NfyaDelivery>> cap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(deliveryMapper).update(isNull(), cap.capture());
        LambdaUpdateWrapper<NfyaDelivery> w = cap.getValue();
        String where = w.getExpression().getNormal().stream().map(ISqlSegment::getSqlSegment)
                .collect(Collectors.joining(" "));
        assertThat(where).contains("id IN");
        assertThat(w.getParamNameValuePairs().containsValue(1L)).isTrue();
        assertThat(w.getParamNameValuePairs().containsValue(3L)).isTrue();
        assertThat(w.getParamNameValuePairs().containsValue("SENDING")).isTrue();

        // 返回原行（引擎按行并行投递，行状态已在 DB 置 SENDING）
        assertThat(claimed).extracting(NfyaDelivery::getId).containsExactly(1L, 2L, 3L);
    }

    @Test
    void claim_empty_batch_skips_sending_update() {
        when(deliveryMapper.claimBatch(any(OffsetDateTime.class), any(int.class)))
                .thenReturn(List.of());

        List<NfyaDelivery> claimed = service.claim(50);

        assertThat(claimed).isEmpty();
        verify(deliveryMapper, never()).update(isNull(), any()); // 空批零回写
    }

    @Test
    void claim_batch_size_is_forwarded_verbatim() {
        when(deliveryMapper.claimBatch(any(OffsetDateTime.class), any(int.class))).thenReturn(List.of());
        service.claim(7);
        verify(deliveryMapper).claimBatch(any(OffsetDateTime.class), org.mockito.ArgumentMatchers.eq(7));
    }
}
