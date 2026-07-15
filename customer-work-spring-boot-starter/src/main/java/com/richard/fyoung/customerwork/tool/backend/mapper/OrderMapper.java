package com.richard.fyoung.customerwork.tool.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.tool.backend.entity.OrderDO;

/**
 * 订单 Mapper（由 {@code CustomerWorkPersistenceConfig} 的 {@code @MapperScan} 扫描绑定，不加 {@code @Mapper}）。
 *
 * <p>查询/改址/取消走 {@link BaseMapper} 与 Wrapper；仅催发货因 {@code CONCAT} 拼接语义写在 XML 中。</p>
 * @author owlzhangfq@gmail.com
 */
public interface OrderMapper extends BaseMapper<OrderDO> {

    /** 催发货：在物流轨迹尾部追加加急标记（对应旧 JdbcOrderBackend 的 urgeShipment UPDATE）。 */
    int urgeShipment(String orderId);
}
