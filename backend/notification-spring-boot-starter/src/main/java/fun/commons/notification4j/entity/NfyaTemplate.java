package fun.commons.notification4j.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 消息模板（DBD 表 9）：{{param}} 占位符 + channel_content 分渠道覆盖。
 * 唯一闸 uk_nfya_template_code (tenant_id, template_code) WHERE is_deleted=0（删后可重建）。
 */
@Data
@TableName(value = "nfya_template", autoResultMap = true)
public class NfyaTemplate {

    private Long id;
    private Long tenantId;
    private String templateCode;
    private String name;
    private String typeCode;
    private String titleTpl;
    private String contentTpl;
    @TableField(typeHandler = fun.commons.notification4j.util.JsonbTypeHandler.class)
    private String channelContent;
    private String status;
    @TableField(typeHandler = fun.commons.notification4j.util.JsonbTypeHandler.class)
    private String ext;
    private java.time.OffsetDateTime createdAt;
    private java.time.OffsetDateTime updatedAt;
    private String createBy;
    private String updateBy;
    @TableLogic
    private Integer isDeleted;
}
