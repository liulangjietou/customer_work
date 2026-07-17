package com.richard.fyoung.customerwork.tool.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.tool.backend.entity.MemberDO;

/**
 * 会员 Mapper（由 {@code CustomerWorkPersistenceConfig} 的 {@code @MapperScan} 扫描绑定，不加 {@code @Mapper}）。
 *
 * <p>积分/等级按主键查询走 {@link BaseMapper#selectById}，无复杂 SQL。</p>
 * @author owlzhangfq@gmail.com
 */
public interface MemberMapper extends BaseMapper<MemberDO> {
}
