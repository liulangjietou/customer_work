package com.richard.fyoung.customerwork.tool.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.tool.backend.entity.InvoiceRequestDO;

/**
 * 发票申请 Mapper（由 {@code CustomerWorkPersistenceConfig} 的 {@code @MapperScan} 扫描绑定，不加 {@code @Mapper}）。
 *
 * <p>仅需 {@link BaseMapper#insert} 落库，无复杂 SQL。</p>
 * @author owlzhangfq@gmail.com
 */
public interface InvoiceRequestMapper extends BaseMapper<InvoiceRequestDO> {
}
