package com.richard.fyoung.customerwork.data.order;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customerwork.core.common.PageResult;
import com.richard.fyoung.customerwork.tool.backend.entity.OrderDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.OrderMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 坐席订单目录服务：为人工客服后台封装订单的多维分页查询、详情、改址与取消。
 *
 * <p>与用户侧 {@code UserOrderDao} 相同的降级策略：通过 {@link ObjectProvider} 注入 {@link OrderMapper}，
 * 全 memory/mock 部署（无 {@code cw_order} 数据源）时 {@link #isEnabled()} 返回 false，接入层据此返回 503，
 * 而非启动即失败。写操作直接走 Mapper 落库并返回结构化结果（{@link OrderMutationResult}），不返回工具文案
 * ——由接入层把领域判定映射为 HTTP 语义（404 / 409）。</p>
 *
 * <p><b>取消约束</b>：仅未发货（待支付 / 已支付 / 待发货）可取消；已发货及之后（已签收 / 已取消 / 已退款等）
 * 返回 {@link OrderMutationResult#STATE_CONFLICT}，由接入层 fast-fail 为 409。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class OrderDirectoryService {

    /** 取消终态（与 {@code MybatisOrderBackend} 保持一致）。 */
    private static final String STATUS_CANCELLED = "已取消";

    /** 可取消的状态集合（未发货阶段）。 */
    private static final List<String> CANCELLABLE_STATUSES = List.of("待支付", "已支付", "待发货");

    private final OrderMapper orderMapper;

    public OrderDirectoryService(ObjectProvider<OrderMapper> orderMapperProvider) {
        this.orderMapper = orderMapperProvider.getIfAvailable();
    }

    /** 订单数据源是否已启用（tool-backend.mode=jdbc 才有 OrderMapper Bean）。 */
    public boolean isEnabled() {
        return orderMapper != null;
    }

    /** 多维分页查询（userId/orderId/status 精确、username 模糊；按下单时间倒序）。 */
    public PageResult<OrderDirectoryRow> page(OrderDirectoryQuery query) {
        Page<OrderDirectoryRow> page = Page.of(query.normalizedPageNum(), query.normalizedPageSize());
        IPage<OrderDirectoryRow> result = orderMapper.pageForAgent(
            page, query.userId(), query.orderId(), query.status(), query.username());
        return new PageResult<>(result.getTotal(), result.getRecords());
    }

    /** 按订单号查详情（含物流轨迹与用户名），不存在返回 empty。 */
    public Optional<OrderDirectoryRow> findDetail(String orderId) {
        return Optional.ofNullable(orderMapper.detailForAgent(orderId));
    }

    /** 改址：订单不存在返回 NOT_FOUND，否则更新收货地址并返回 OK。 */
    public OrderMutationResult modifyAddress(String orderId, String newAddress) {
        OrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            return OrderMutationResult.NOT_FOUND;
        }
        orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>()
            .set(OrderDO::getReceiverAddr, newAddress)
            .eq(OrderDO::getOrderId, orderId));
        return OrderMutationResult.OK;
    }

    /** 取消：不存在 NOT_FOUND，已发货及之后 STATE_CONFLICT，否则置为已取消并返回 OK。 */
    public OrderMutationResult cancel(String orderId, String reason) {
        OrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            return OrderMutationResult.NOT_FOUND;
        }
        if (!CANCELLABLE_STATUSES.contains(order.getStatus())) {
            return OrderMutationResult.STATE_CONFLICT;
        }
        orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>()
            .set(OrderDO::getStatus, STATUS_CANCELLED)
            .eq(OrderDO::getOrderId, orderId));
        return OrderMutationResult.OK;
    }
}
