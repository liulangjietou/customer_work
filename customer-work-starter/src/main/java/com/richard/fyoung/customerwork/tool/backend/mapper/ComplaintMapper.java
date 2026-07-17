package com.richard.fyoung.customerwork.tool.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.tool.backend.entity.ComplaintDO;

/**
 * 投诉工单 Mapper（由 {@code @MapperScan} 扫描绑定，不加 {@code @Mapper}）。
 *
 * <p>建单走 {@link BaseMapper#insert}，按工单号查询走 {@link BaseMapper#selectById}，无复杂 SQL。</p>
 * @author owlzhangfq@gmail.com
 */
public interface ComplaintMapper extends BaseMapper<ComplaintDO> {
}
