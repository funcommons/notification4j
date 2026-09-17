package fun.commons.notification4j.engine;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import fun.commons.notification4j.entity.NfyaDelivery;
import fun.commons.notification4j.mapper.NfyaDeliveryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 投递领取（短事务）：FOR UPDATE SKIP LOCKED 锁行 → 置 SENDING → 提交。
 * 独立 Bean 保证事务代理生效（引擎调度线程为外部调用方）。
 */
@Service
@RequiredArgsConstructor
public class DeliveryClaimService {

    private final NfyaDeliveryMapper deliveryMapper;

    @Transactional
    public List<NfyaDelivery> claim(int batch) {
        List<NfyaDelivery> rows = deliveryMapper.claimBatch(OffsetDateTime.now(), batch);
        if (rows.isEmpty()) {
            return rows;
        }
        // 单语句批量置 SENDING（评审第 6b 步 P2：替代逐行 UPDATE 的 N+1）
        List<Long> ids = rows.stream().map(NfyaDelivery::getId).toList();
        deliveryMapper.update(null, new LambdaUpdateWrapper<NfyaDelivery>()
                .in(NfyaDelivery::getId, ids)
                .set(NfyaDelivery::getStatus, "SENDING"));
        return rows;
    }
}
