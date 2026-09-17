package fun.commons.notification4j.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 公告（DBD 表 5）：平台公告（tenant_id=0）+ 租户公告，fanout-on-read 写侧。
 * 幂等闸 uk_nfya_ann_tenant_biz_no (tenant_id, biz_no) WHERE biz_no <> ''。
 */
@Data
@TableName(value = "nfya_announcement", autoResultMap = true)
public class NfyaAnnouncement {

    private Long id;
    private Long tenantId;
    private String bizNo;
    private String scope;
    private String title;
    private String content;
    private String level;
    private Integer needConfirm;
    private String linkUrl;
    @TableField(typeHandler = fun.commons.notification4j.util.JsonbTypeHandler.class)
    private String channelIds;
    private String status;
    private java.time.OffsetDateTime effectiveAt;
    private java.time.OffsetDateTime expireAt;
    private java.time.OffsetDateTime publishedAt;
    private Integer confirmCount;
    @TableField(typeHandler = fun.commons.notification4j.util.JsonbTypeHandler.class)
    private String ext;
    private java.time.OffsetDateTime createdAt;
    private java.time.OffsetDateTime updatedAt;
    private String createBy;
    private String updateBy;
    @TableLogic
    private Integer isDeleted;
}
