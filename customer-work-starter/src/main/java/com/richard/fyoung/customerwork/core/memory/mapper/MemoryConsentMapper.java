package com.richard.fyoung.customerwork.core.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.core.memory.entity.MemoryConsentDO;
import org.apache.ibatis.annotations.Param;

/** 长期记忆同意 Mapper。 */
public interface MemoryConsentMapper extends BaseMapper<MemoryConsentDO> {

    /** 按主体唯一键幂等写入，重复授权/撤回只更新状态和时间。 */
    int upsert(@Param("record") MemoryConsentDO record);

    /** 删除超过审计保留期的撤回记录；有效授权绝不能被定时任务删除。 */
    int deleteWithdrawnBefore(@Param("cutoffMs") long cutoffMs, @Param("limit") int limit);
}
