package fun.commons.notification4j.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 消息主表（DBD 表 3）：定向消息，fanout-on-write 写侧。
 * 幂等真闸 = UNIQUE(tenant_id, biz_no) 部分唯一索引。
 */
@Data
@TableName(value = "nfya_message", autoResultMap = true)
public class NfyaMessage {

    private Long id;
    private Long tenantId;
    private String bizNo;
    private String typeCode;
    private String level;
    private String title;
    private String content;
    private String linkUrl;
    private Long templateId;
    @TableField(typeHandler = fun.commons.notification4j.util.JsonbTypeHandler.class)
    private String params;
    private Integer receiverCount;
    private String status;
    private String sender;
    @TableField(typeHandler = fun.commons.notification4j.util.JsonbTypeHandler.class)
    private String ext;
    private java.time.OffsetDateTime createdAt;
    private java.time.OffsetDateTime updatedAt;
    private String createBy;
    private String updateBy;
    @TableLogic
    private Integer isDeleted;
}
