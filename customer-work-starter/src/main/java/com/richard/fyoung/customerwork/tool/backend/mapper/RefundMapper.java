package com.richard.fyoung.customerwork.tool.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.tool.backend.entity.RefundDO;
import org.apache.ibatis.annotations.Param;

/**
 * 售后工单 Mapper（由 {@code CustomerWorkPersistenceConfig} 的 {@code @MapperScan} 扫描绑定，不加 {@code @Mapper}）。
 *
 * <p>售后申请与状态查询在 SQL 中关联同租户、同用户的真实订单，不能只按订单号访问。</p>
 * @author owlzhangfq@gmail.com
 */
public interface RefundMapper extends BaseMapper<RefundDO> {

    /** 在同一条 SQL 中校验订单归属并创建申请；订单不可访问时返回 0。 */
    int insertForOwner(@Param("record") RefundDO record, @Param("userId") String userId);

    /** 查询认证用户自有订单的最近一笔工单状态，无记录返回 null。 */
    String queryLatestRefundStatus(@Param("tenantId") String tenantId, @Param("userId") String userId,
                                   @Param("orderId") String orderId, @Param("type") String type);
}
