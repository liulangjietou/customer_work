package com.richard.fyoung.customerwork.core.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.core.memory.entity.HarnessMemoryDO;

/**
 * Harness 分层记忆 Mapper：继承 {@link BaseMapper} 复用单表 CRUD（查询 / 删除走 LambdaQueryWrapper），
 * {@link #upsert} 表达按 {@code scope_hash} 的 {@code INSERT ... ON DUPLICATE KEY UPDATE}。
 * @author owlzhangfq@gmail.com
 */
public interface HarnessMemoryMapper extends BaseMapper<HarnessMemoryDO> {

    /** 按 {@code uk_harness_memory_scope} upsert：同一 workspace 的记忆始终只有一行，以最新一次为准。 */
    int upsert(HarnessMemoryDO record);
}
