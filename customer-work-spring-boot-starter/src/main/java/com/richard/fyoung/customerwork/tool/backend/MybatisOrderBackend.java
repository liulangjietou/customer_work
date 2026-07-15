package com.richard.fyoung.customerwork.tool.backend;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.richard.fyoung.customerwork.tool.backend.entity.OrderDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.function.IntSupplier;

/**
 * 订单后端的 MyBatis-Plus 实现：从 {@code cw_order} 表读真实订单，写操作（改址 / 取消 / 催发货）真实 UPDATE。
 *
 * <p>输出文案对齐 {@link MockOrderBackend}，切到 jdbc 模式后系统提示词示例连续（种子数据集中在
 * {@code customer-work-schema.sql}）。查询/改址/取消走 {@link OrderMapper}（BaseMapper + Wrapper），
 * 仅催发货因 {@code CONCAT} 语义走 XML。本类由 starter 的 {@code ToolBackendConfig} 在
 * {@code tool-backend.mode=jdbc} 时装配。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisOrderBackend implements OrderBackend {

    private static final Logger log = LoggerFactory.getLogger(MybatisOrderBackend.class);

    /** 订单取消终态。 */
    private static final String STATUS_CANCELLED = "已取消";

    private final OrderMapper orderMapper;

    public MybatisOrderBackend(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Override
    public Mono<String> queryOrder(String orderId) {
        return Mono.fromSupplier(() -> doQueryOrder(orderId));
    }

    @Override
    public Mono<String> queryLogistics(String orderId) {
        return Mono.fromSupplier(() -> doQueryLogistics(orderId));
    }

    @Override
    public Mono<String> modifyAddress(String orderId, String newAddress) {
        return Mono.fromSupplier(() -> doModifyAddress(orderId, newAddress));
    }

    @Override
    public Mono<String> cancelOrder(String orderId, String reason) {
        return Mono.fromSupplier(() -> doCancelOrder(orderId, reason));
    }

    @Override
    public Mono<String> urgeShipment(String orderId) {
        return Mono.fromSupplier(() -> doUrgeShipment(orderId));
    }

    private String doQueryOrder(String orderId) {
        try {
            OrderDO order = orderMapper.selectById(orderId);
            if (order == null) {
                return "未查询到订单 " + orderId + "，请用户核对订单号。";
            }
            return String.format("订单 %s：状态=%s，金额=%s 元，下单时间=%s。",
                orderId, order.getStatus(), order.getAmount().toPlainString(),
                formatDate(order.getCreatedAtMs()));
        } catch (Exception e) {
            log.error("order query failed, code={}, orderId={}", "ORDER-BACKEND-QUERY-FAIL", orderId, e);
            return "订单系统暂时不可用，建议稍后再试或转人工。";
        }
    }

    private String doQueryLogistics(String orderId) {
        try {
            OrderDO order = orderMapper.selectById(orderId);
            if (order == null) {
                return "未查询到订单 " + orderId + " 的物流信息。";
            }
            String trace = order.getLogisticsTrace();
            if (trace == null || trace.isBlank()) {
                return "订单 " + orderId + " 暂无物流轨迹。";
            }
            return "订单 " + orderId + " 物流：" + trace;
        } catch (Exception e) {
            log.error("logistics query failed, code={}, orderId={}", "ORDER-BACKEND-LOGISTICS-FAIL", orderId, e);
            return "物流系统暂时不可用，建议稍后再试。";
        }
    }

    private String doModifyAddress(String orderId, String newAddress) {
        int affected = runUpdate(() -> orderMapper.update(null,
            new LambdaUpdateWrapper<OrderDO>()
                .set(OrderDO::getReceiverAddr, newAddress)
                .eq(OrderDO::getOrderId, orderId)),
            "ORDER-BACKEND-MODIFYADDR-FAIL", orderId);
        return affected > 0
            ? "订单 " + orderId + " 收货地址已更新为「" + newAddress + "」，将按新地址派送。"
            : "未查询到订单 " + orderId + "，无法改址，请核对订单号。";
    }

    private String doCancelOrder(String orderId, String reason) {
        int affected = runUpdate(() -> orderMapper.update(null,
            new LambdaUpdateWrapper<OrderDO>()
                .set(OrderDO::getStatus, STATUS_CANCELLED)
                .eq(OrderDO::getOrderId, orderId)),
            "ORDER-BACKEND-CANCEL-FAIL", orderId);
        return affected > 0
            ? "订单 " + orderId + " 已受理取消申请（原因：" + reason + "）；若已支付，款项原路退回。"
            : "未查询到订单 " + orderId + "，无法取消，请核对订单号。";
    }

    private String doUrgeShipment(String orderId) {
        int affected = runUpdate(() -> orderMapper.urgeShipment(orderId), "ORDER-BACKEND-URGE-FAIL", orderId);
        return affected > 0
            ? "已为订单 " + orderId + " 提交加急发货标记，仓库将优先处理，预计 24 小时内出库。"
            : "未查询到订单 " + orderId + "，请核对订单号。";
    }

    /** 统一执行 UPDATE，失败记错误码并返回 0（沿用旧 JdbcOrderBackend.update 的静默降级语义）。 */
    private int runUpdate(IntSupplier update, String errorCode, String orderId) {
        try {
            return update.getAsInt();
        } catch (Exception e) {
            log.error("order update failed, code={}, orderId={}", errorCode, orderId, e);
            return 0;
        }
    }

    private static String formatDate(long ms) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC).toString();
    }
}
