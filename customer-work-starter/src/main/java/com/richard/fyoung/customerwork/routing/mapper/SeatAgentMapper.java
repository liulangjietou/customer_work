package com.richard.fyoung.customerwork.routing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.routing.entity.SeatAgentDO;

/**
 * 坐席 Mapper（由 {@code CustomerWorkPersistenceConfig} 的 {@code @MapperScan} 扫描绑定，不加 {@code @Mapper}）：
 * 继承 {@link BaseMapper} 复用单表 CRUD；{@link #upsert} 表达按主键的 {@code INSERT ... ON DUPLICATE KEY UPDATE}。
 * @author owlzhangfq@gmail.com
 */
public interface SeatAgentMapper extends BaseMapper<SeatAgentDO> {

    /** 按主键 upsert：存在则更新名称/技能/负载/在线/分组/更新时间，不存在则插入。 */
    int upsert(SeatAgentDO record);
}
