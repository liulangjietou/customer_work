package com.richard.fyoung.customerwork.handoff.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.handoff.entity.HandoffTicketDO;

/**
 * 人机切换工单 Mapper：继承 {@link BaseMapper} 复用单表 CRUD；
 * {@link #upsert} 表达 {@code INSERT ... ON DUPLICATE KEY UPDATE}（BaseMapper 无原生 upsert）。
 * @author owlzhangfq@gmail.com
 */
public interface HandoffMapper extends BaseMapper<HandoffTicketDO> {

    /** 按主键 upsert：存在则更新状态/接单/结案相关列，不存在则插入。 */
    int upsert(HandoffTicketDO record);
}
