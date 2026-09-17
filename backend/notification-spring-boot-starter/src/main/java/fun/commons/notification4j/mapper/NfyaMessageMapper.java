package fun.commons.notification4j.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import fun.commons.notification4j.entity.NfyaMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NfyaMessageMapper extends BaseMapper<NfyaMessage> {
}
