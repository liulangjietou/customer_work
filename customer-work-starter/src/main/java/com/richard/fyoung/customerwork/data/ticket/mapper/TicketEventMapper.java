package com.richard.fyoung.customerwork.data.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.data.ticket.entity.TicketEventDO;

/**
 * 工单事件轨迹 Mapper。追加走 {@link BaseMapper#insert}（AUTO 主键回填），
 * 查询走 {@code selectList(QueryWrapper)}，无需 XML。
 * @author owlzhangfq@gmail.com
 */
public interface TicketEventMapper extends BaseMapper<TicketEventDO> {
}
