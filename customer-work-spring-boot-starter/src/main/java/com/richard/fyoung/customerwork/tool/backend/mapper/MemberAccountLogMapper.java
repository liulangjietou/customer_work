package com.richard.fyoung.customerwork.tool.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.tool.backend.entity.MemberAccountLogDO;

/**
 * 会员账户问题处理日志 Mapper（由 {@code @MapperScan} 扫描绑定，不加 {@code @Mapper}）。
 *
 * <p>仅需 {@link BaseMapper#insert} 落库，无复杂 SQL。</p>
 * @author owlzhangfq@gmail.com
 */
public interface MemberAccountLogMapper extends BaseMapper<MemberAccountLogDO> {
}
