package fun.commons.notification4j.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import fun.commons.framework4j.tenant.entity.TenantEntity;

/**
 * 租户配置表实体(实体子类 SPI,framework4j-tenant 接入)。
 * <p>
 * 字段全部继承 {@link TenantEntity}(契约层冻结:id=租户 id 雪花/四类配置 JSONB/
 * 密钥 AES-GCM 双列宽限期/生命周期状态机);表名守项目简码规范 nfya_tenant。
 * {@code autoResultMap = true} 必须 —— 密钥列 typeHandler select 解密依赖它。
 */
@TableName(value = "nfya_tenant", autoResultMap = true)
public class NfyaTenant extends TenantEntity {
}
