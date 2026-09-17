package fun.commons.notification4j.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 外发投递记录（DBD 表 10）：消息/公告 × 渠道的投递留痕（状态机+重试）。
 * 幂等真闸 uk_nfya_delivery_source (tenant_id, source_type, source_id, userid, channel_id)；
 * 扫描走部分索引 idx_nfya_delivery_pending (next_retry_at) WHERE status IN(PENDING,FAILED)。
 */
@Data
@TableName(value = "nfya_delivery", autoResultMap = true)
public class NfyaDelivery {

    private Long id;
    private Long tenantId;
    private String sourceType;
    private Long sourceId;
    private String userid;
    private Long channelId;
    private String channelType;
    private String target;
    private String title;
    private String status;
    private Integer retryCount;
    private java.time.OffsetDateTime nextRetryAt;
    private String errorMessage;
    private String traceId;
    private java.time.OffsetDateTime sentAt;
    @TableField(typeHandler = fun.commons.notification4j.util.JsonbTypeHandler.class)
    private String ext;
    private java.time.OffsetDateTime createdAt;
    private java.time.OffsetDateTime updatedAt;
    private String createBy;
    private String updateBy;
    @TableLogic
    private Integer isDeleted;
}
