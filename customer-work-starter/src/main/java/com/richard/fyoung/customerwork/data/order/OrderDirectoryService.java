package com.richard.fyoung.customerwork.data.order;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customerwork.core.common.PageResult;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.tool.backend.mapper.OrderMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Objects;
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
            page, TenantContext.require(), query.userId(), query.orderId(), query.status(), query.username());
        return new PageResult<>(result.getTotal(), result.getRecords());
    }

    /** 按订单号查详情（含物流轨迹与用户名），不存在返回 empty。 */
    public Optional<OrderDirectoryRow> findDetail(String orderId) {
        return Optional.ofNullable(orderMapper.detailForAgent(TenantContext.require(), orderId));
    }

    /** 改址只更新当前租户；零行时核对当前事实，不能把并发删除报告为成功。 */
    public OrderMutationResult modifyAddress(String orderId, String newAddress) {
        int affected = orderMapper.modifyAddressForAgent(TenantContext.require(), orderId, newAddress);
        if (affected == 1) {
            return OrderMutationResult.OK;
        }
        return findDetail(orderId).map(order -> Objects.equals(order.getReceiverAddr(), newAddress)
            ? OrderMutationResult.OK : OrderMutationResult.STATE_CONFLICT).orElse(OrderMutationResult.NOT_FOUND);
    }

    /** 状态条件与更新原子执行；并发发货返回冲突，并发删除返回不存在。 */
    public OrderMutationResult cancel(String orderId, String reason) {
        int affected = orderMapper.cancelForAgent(TenantContext.require(), orderId,
            OrderStatuses.CANCELLABLE, OrderStatuses.CANCELLED);
        if (affected == 1) {
            return OrderMutationResult.OK;
        }
        return findDetail(orderId).isPresent() ? OrderMutationResult.STATE_CONFLICT : OrderMutationResult.NOT_FOUND;
    }
}
