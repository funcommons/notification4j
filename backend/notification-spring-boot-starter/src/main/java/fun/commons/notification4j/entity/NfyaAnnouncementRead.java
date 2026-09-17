package fun.commons.notification4j.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 公告回执（DBD 表 6）：fanout-on-read 读侧。
 * 幂等闸 uk_nfya_ann_read_user (tenant_id, announcement_id, userid)；读者 tenant_id 各属其租户。
 */
@Data
@TableName(value = "nfya_announcement_read", autoResultMap = true)
public class NfyaAnnouncementRead {

    private Long id;
    private Long tenantId;
    private Long announcementId;
    private String userid;
    private java.time.OffsetDateTime readAt;
    private java.time.OffsetDateTime confirmAt;
    @TableField(typeHandler = fun.commons.notification4j.util.JsonbTypeHandler.class)
    private String ext;
    private java.time.OffsetDateTime createdAt;
    private java.time.OffsetDateTime updatedAt;
    private String createBy;
    private String updateBy;
    @TableLogic
    private Integer isDeleted;
}
