package com.richard.fyoung.customerwork.tool.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.tool.backend.entity.RefundDO;
import org.apache.ibatis.annotations.Param;

/**
 * 售后工单 Mapper（由 {@code CustomerWorkPersistenceConfig} 的 {@code @MapperScan} 扫描绑定，不加 {@code @Mapper}）。
 *
 * <p>工单落库走 {@link BaseMapper#insert}；仅最近一笔退款状态查询因 {@code ORDER BY ... LIMIT 1} 语义写在 XML 中。</p>
 * @author owlzhangfq@gmail.com
 */
public interface RefundMapper extends BaseMapper<RefundDO> {

    /** 查询某订单指定类型的最近一笔工单状态，无记录返回 null。 */
    String queryLatestRefundStatus(@Param("orderId") String orderId, @Param("type") String type);
}
