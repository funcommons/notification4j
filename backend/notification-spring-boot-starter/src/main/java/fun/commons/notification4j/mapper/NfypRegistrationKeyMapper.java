package fun.commons.notification4j.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import fun.commons.notification4j.entity.NfypRegistrationKey;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 注册码 mapper；consume 为单语句原子扣减（V1.0 无 Redis 扣减——低频写场景，技术方案 Redis 优化登记） */
public interface NfypRegistrationKeyMapper extends BaseMapper<NfypRegistrationKey> {

    @Update("""
            UPDATE nfyp_registration_key
            SET used_count = used_count + 1, consumed_tenant_id = #{tenantId}, updated_at = now()
            WHERE code = #{code} AND is_deleted = 0
              AND status = 'ACTIVE' AND expire_at > now()
              AND used_count < max_uses
            """)
    int consume(@Param("code") String code, @Param("tenantId") long tenantId);
}
