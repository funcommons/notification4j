package fun.commons.notification4j.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 消息类型（DBD 表 2）：租户自定义 + 内置类型，订阅/模板/统计的维度。
 * mandatory=1 时 default_channels 即订阅矩阵强制渠道集（仅平台域可设）。
 */
@Data
@TableName(value = "nfya_message_type", autoResultMap = true)
public class NfyaMessageType {

    private Long id;
    private Long tenantId;
    private String typeCode;
    private String name;
    private String description;
    private String defaultLevel;
    /** JSONB 数组, PG stringtype=unspecified 下直接以字符串写入 */
    @TableField(typeHandler = fun.commons.notification4j.util.JsonbTypeHandler.class)
    private String defaultChannels;
    private Integer mandatory;
    private Integer builtIn;
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
