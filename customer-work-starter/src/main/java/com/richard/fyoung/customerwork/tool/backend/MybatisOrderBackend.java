package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.data.order.OrderStatuses;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.tool.backend.entity.OrderDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.OrderMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

/** 订单工具的真实数据库后端：认证 USER 自有订单，方法调用时冻结身份，SQL 显式限定租户和用户。 */
public class MybatisOrderBackend implements OrderBackend {
    private static final Logger log = LoggerFactory.getLogger(MybatisOrderBackend.class);
    private static final String ERROR_QUERY = "ORDER-BACKEND-QUERY-FAIL";
    private static final String ERROR_LOGISTICS = "ORDER-BACKEND-LOGISTICS-FAIL";
    private static final String ERROR_ADDRESS = "ORDER-BACKEND-MODIFYADDR-FAIL";
    private static final String ERROR_CANCEL = "ORDER-BACKEND-CANCEL-FAIL";
    private static final String ERROR_URGE = "ORDER-BACKEND-URGE-FAIL";
    private final OrderMapper orderMapper;

    public MybatisOrderBackend(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    /** 查询自有订单的实际状态、金额与下单时间。 */
    @Override
    public Mono<String> queryOrder(String orderId) {
        return execute(orderId, ERROR_QUERY, identity -> {
            OrderDO order = requireOwnedOrder(identity, orderId);
            return String.format("订单 %s：状态=%s，金额=%s 元，下单时间=%s。", orderId, order.getStatus(),
                order.getAmount().toPlainString(), formatDate(order.getCreatedAtMs()));
        });
    }

    /** 查询已保存的物流轨迹，不推测仓库或快递执行结果。 */
    @Override
    public Mono<String> queryLogistics(String orderId) {
        return execute(orderId, ERROR_LOGISTICS, identity -> {
            String trace = requireOwnedOrder(identity, orderId).getLogisticsTrace();
            return StringUtils.hasText(trace) ? "订单 " + orderId + " 物流：" + trace : "订单 " + orderId + " 暂无物流轨迹。";
        });
    }

    /** 改址与归属检查在同一条 UPDATE 完成；沿用现有改址状态契约。 */
    @Override
    public Mono<String> modifyAddress(String orderId, String newAddress) {
        return execute(orderId, ERROR_ADDRESS, identity -> {
            int affected = orderMapper.modifyAddressForOwner(identity.tenantId(), identity.subjectId(), orderId, newAddress);
            if (affected != 1) {
                // 驱动可配置为只报告实际变更行；重复提交同一地址仍按当前自有订单事实确认。
                OrderDO current = requireOwnedOrder(identity, orderId);
                if (!Objects.equals(current.getReceiverAddr(), newAddress)) {
                    throw new IllegalStateException("订单地址未更新，请重新核对订单状态。");
                }
            }
            return "订单 " + orderId + " 收货地址已更新为「" + newAddress + "」。";
        });
    }

    /** 仅更新尚未发货的自有订单；状态条件参与 UPDATE，不覆盖并发发货，也不执行退款。 */
    @Override
    public Mono<String> cancelOrder(String orderId, String reason) {
        return execute(orderId, ERROR_CANCEL, identity -> {
            if (orderMapper.cancelForOwner(identity.tenantId(), identity.subjectId(), orderId,
                OrderStatuses.CANCELLABLE, OrderStatuses.CANCELLED) != 1) {
                requireOwnedOrder(identity, orderId);
                throw new IllegalStateException("当前订单状态不允许取消，请重新核对订单状态。");
            }
            return "订单 " + orderId + " 已取消；本操作未执行退款。";
        });
    }

    /** 只登记真实加急标记，不承诺仓库执行顺序或出库时效。 */
    @Override
    public Mono<String> urgeShipment(String orderId) {
        return execute(orderId, ERROR_URGE, identity -> {
            if (orderMapper.urgeShipmentForOwner(identity.tenantId(), identity.subjectId(), orderId) != 1) {
                throw orderUnavailable();
            }
            return "订单 " + orderId + " 已登记加急发货标记，实际发货进度请以订单物流信息为准。";
        });
    }

    private Mono<String> execute(String orderId, String errorCode, Function<AgentInvocationIdentity, String> action) {
        AgentInvocationIdentity identity = AgentInvocationIdentity.capture();
        return Mono.fromSupplier(() -> {
            if (identity == null || !identity.authenticated() || identity.subjectType() != QuotaSubjectType.USER
                || !TenantContext.isValidTenantId(identity.tenantId()) || !StringUtils.hasText(identity.subjectId())) {
                throw new SecurityException("authenticated order user identity is required");
            }
            return TenantContext.callWith(identity.tenantId(), () -> action.apply(identity));
        }).doOnError(error -> log.error("order operation failed, errorCode={}, orderId={}", errorCode, orderId, error))
            // Toolkit 会解包根异常；私有 SQL 原因保留在服务端，不能进入模型结果。
            .onErrorMap(DataAccessException.class, error -> new IllegalStateException("订单服务暂时不可用，请稍后重试。"));
    }

    private OrderDO requireOwnedOrder(AgentInvocationIdentity identity, String orderId) {
        OrderDO order = orderMapper.findOwned(identity.tenantId(), identity.subjectId(), orderId);
        if (order == null) {
            throw orderUnavailable();
        }
        return order;
    }

    private static NoSuchElementException orderUnavailable() {
        return new NoSuchElementException("订单不存在或不可访问。");
    }

    private static String formatDate(long timestamp) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC).toString();
    }
}
