package fun.commons.notification4j.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 订阅偏好（DBD 表 8）：用户 × 消息类型 × 渠道集合。
 * 唯一闸 uk_nfya_subscription_user_type (tenant_id, userid, type_code)；
 * channel_ids = ["INAPP", "<nfya_channel.id>"]（INAPP 哨兵恒在）。
 */
@Data
@TableName(value = "nfya_subscription", autoResultMap = true)
public class NfyaSubscription {

    private Long id;
    private Long tenantId;
    private String userid;
    private String typeCode;
    @TableField(typeHandler = fun.commons.notification4j.util.JsonbTypeHandler.class)
    private String channelIds;
    @TableField(typeHandler = fun.commons.notification4j.util.JsonbTypeHandler.class)
    private String quietHours;
    @TableField(typeHandler = fun.commons.notification4j.util.JsonbTypeHandler.class)
    private String ext;
    private java.time.OffsetDateTime createdAt;
    private java.time.OffsetDateTime updatedAt;
    private String createBy;
    private String updateBy;
    @TableLogic
    private Integer isDeleted;
}
