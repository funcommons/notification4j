package fun.commons.notification4j.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import fun.commons.framework4j.sensitive.typehandler.LazyEncryptedFieldTypeHandler;
import lombok.Data;

/**
 * 渠道（DBD 表 7）：用户自注册 + 租户公共渠道（scope=USER/TENANT）。
 * 唯一闸 uk_nfya_channel_target (tenant_id, userid, channel_type, md5(target))；
 * secret 走框架 LazyEncryptedFieldTypeHandler 自动 AES-GCM 加解密（读出即明文）。
 */
@Data
@TableName(value = "nfya_channel", autoResultMap = true)
public class NfyaChannel {

    private Long id;
    private Long tenantId;
    private String scope;
    private String userid;
    private String channelType;
    private String name;
    private String target;
    @TableField(typeHandler = LazyEncryptedFieldTypeHandler.class)
    private String secret;
    private String keyword;
    private String status;
    private Integer failCount;
    private java.time.OffsetDateTime lastVerifyAt;
    @TableField(typeHandler = fun.commons.notification4j.util.JsonbTypeHandler.class)
    private String ext;
    private java.time.OffsetDateTime createdAt;
    private java.time.OffsetDateTime updatedAt;
    private String createBy;
    private String updateBy;
    @TableLogic
    private Integer isDeleted;
}
