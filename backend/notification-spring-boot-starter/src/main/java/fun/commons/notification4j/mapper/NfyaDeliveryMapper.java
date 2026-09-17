package fun.commons.notification4j.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import fun.commons.notification4j.entity.NfyaDelivery;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

/** 外发投递 mapper（@MapperScan 扫描注册；引擎领取走 SKIP LOCKED 原生 SQL） */
public interface NfyaDeliveryMapper extends BaseMapper<NfyaDelivery> {

    /**
     * 引擎领取（技术方案 §4.3）：待投部分索引命中 + JOIN 渠道须 ENABLED（停用/熔断/已删渠道
     * 的行留在原地等待恢复，不计重试）+ FOR UPDATE SKIP LOCKED 多实例安全；领取后同事务置 SENDING。
     */
    @Select("""
            SELECT d.* FROM nfya_delivery d
            JOIN nfya_channel c ON c.id = d.channel_id AND c.status = 'ENABLED' AND c.is_deleted = 0
            WHERE d.is_deleted = 0 AND d.status IN ('PENDING', 'FAILED') AND d.next_retry_at <= #{now}
            ORDER BY d.next_retry_at
            LIMIT #{batch}
            FOR UPDATE OF d SKIP LOCKED
            """)
    List<NfyaDelivery> claimBatch(@Param("now") OffsetDateTime now, @Param("batch") int batch);

    /** Reaper：SENDING 超时未完成（进程崩溃残留）回收为 PENDING（updated_at 由 V1.0.1 触发器维护） */
    @Select("""
            SELECT * FROM nfya_delivery
            WHERE is_deleted = 0 AND status = 'SENDING' AND updated_at < #{staleBefore}
            LIMIT #{batch}
            """)
    List<NfyaDelivery> findStaleSending(@Param("staleBefore") OffsetDateTime staleBefore, @Param("batch") int batch);
}
