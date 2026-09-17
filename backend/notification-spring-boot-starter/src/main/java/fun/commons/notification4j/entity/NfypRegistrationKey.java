package fun.commons.notification4j.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 注册码（DBD 表 11，平台层资源无 tenant_id；消费后回填 consumed_tenant_id）。
 * 唯一闸 uk_nfyp_reg_key_code (code) WHERE is_deleted=0。
 */
@Data
@TableName(value = "nfyp_registration_key", autoResultMap = true)
public class NfypRegistrationKey {

    private Long id;
    private String code;
    private Integer maxUses;
    private Integer usedCount;
    @TableField(typeHandler = fun.commons.notification4j.util.JsonbTypeHandler.class)
    private String preset;
    private String status;
    private java.time.OffsetDateTime expireAt;
    private Long consumedTenantId;
    private String issueBy;
    @TableField(typeHandler = fun.commons.notification4j.util.JsonbTypeHandler.class)
    private String ext;
    private java.time.OffsetDateTime createdAt;
    private java.time.OffsetDateTime updatedAt;
    private String createBy;
    private String updateBy;
    @TableLogic
    private Integer isDeleted;
}
