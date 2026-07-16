package com.richard.fyoung.customerwork.tool.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customerwork.order.OrderDirectoryRow;
import com.richard.fyoung.customerwork.tool.backend.entity.OrderDO;
import org.apache.ibatis.annotations.Param;

/**
 * 订单 Mapper（由 {@code CustomerWorkPersistenceConfig} 的 {@code @MapperScan} 扫描绑定，不加 {@code @Mapper}）。
 *
 * <p>查询/改址/取消走 {@link BaseMapper} 与 Wrapper；催发货（{@code CONCAT}）与坐席侧多维查询
 * （JOIN {@code cw_user} 取用户名）因需自定义 SQL 写在 XML 中。</p>
 * @author owlzhangfq@gmail.com
 */
public interface OrderMapper extends BaseMapper<OrderDO> {

    /** 催发货：在物流轨迹尾部追加加急标记（对应旧 JdbcOrderBackend 的 urgeShipment UPDATE）。 */
    int urgeShipment(String orderId);

    /**
     * 坐席侧订单分页：JOIN {@code cw_user} 带出用户名，按下单时间倒序。各过滤项为空则不限制
     * （userId/orderId/status 精确、username 模糊）。分页由 {@code PaginationInnerInterceptor} 自动接管。
     */
    IPage<OrderDirectoryRow> pageForAgent(Page<OrderDirectoryRow> page,
                                          @Param("userId") String userId,
                                          @Param("orderId") String orderId,
                                          @Param("status") String status,
                                          @Param("username") String username);

    /** 坐席侧订单详情（含物流轨迹与用户名），不存在返回 null。 */
    OrderDirectoryRow detailForAgent(@Param("orderId") String orderId);
}
