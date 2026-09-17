package fun.commons.notification4j.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 消息接收记录（DBD 表 4）：接收人 + 已读状态（消息中心读侧主表）。
 * 唯一闸 UNIQUE(tenant_id, message_id, userid)；未读走部分索引 idx_nfya_recipient_unread。
 */
@Data
@TableName(value = "nfya_message_recipient", autoResultMap = true)
public class NfyaMessageRecipient {

    private Long id;
    private Long tenantId;
    private Long messageId;
    private String userid;
    private String readStatus;
    private java.time.OffsetDateTime readAt;
    @TableField(typeHandler = fun.commons.notification4j.util.JsonbTypeHandler.class)
    private String ext;
    private java.time.OffsetDateTime createdAt;
    private java.time.OffsetDateTime updatedAt;
    private String createBy;
    private String updateBy;
    @TableLogic
    private Integer isDeleted;
}
