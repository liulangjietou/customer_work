package com.richard.fyoung.customerwork.tool.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.tool.backend.entity.InvoiceRequestDO;
import org.apache.ibatis.annotations.Param;

/**
 * 发票申请 Mapper（由 {@code CustomerWorkPersistenceConfig} 的 {@code @MapperScan} 扫描绑定，不加 {@code @Mapper}）。
 *
 * <p>发票申请必须与认证用户的真实订单关联，归属校验与创建由同一条 SQL 完成。</p>
 * @author owlzhangfq@gmail.com
 */
public interface InvoiceRequestMapper extends BaseMapper<InvoiceRequestDO> {

    /** 校验同租户用户的订单归属后创建申请；订单不可访问时返回 0，并回填创建的主键。 */
    int insertForOwner(@Param("record") InvoiceRequestDO record, @Param("userId") String userId);
}
