package com.richard.fyoung.customerwork.tool.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * 订单后端的默认演示实现（内存 Mock）。由 {@code ToolBackendConfig} 以
 * {@code @ConditionalOnMissingBean} 注册为默认 Bean；生产提供自定义 {@link OrderBackend} Bean 即可覆盖。
 * @author owlzhangfq@gmail.com
 */
public class MockOrderBackend implements OrderBackend {

    private static final Logger log = LoggerFactory.getLogger(MockOrderBackend.class);

    private static final Map<String, Map<String, String>> MOCK_ORDERS = Map.of(
        "20260613001", Map.of("status", "已发货", "amount", "299.00", "createTime", "2026-06-10"),
        "20260613002", Map.of("status", "已签收", "amount", "1599.00", "createTime", "2026-05-20")
    );

    @Override
    public Mono<String> queryOrder(String orderId) {
        log.info("[MockOrderBackend] 查询订单: {}", orderId);
        return Mono.fromSupplier(() -> {
                Map<String, String> order = MOCK_ORDERS.get(orderId);
                if (order == null) {
                    return "未查询到订单 " + orderId + "，请用户核对订单号。";
                }
                return String.format("订单 %s：状态=%s，金额=%s 元，下单时间=%s。",
                    orderId, order.get("status"), order.get("amount"), order.get("createTime"));
            })
            .delayElement(Duration.ofMillis(120))
            .onErrorResume(e -> Mono.just("订单系统暂时不可用，建议稍后再试或转人工。"));
    }

    @Override
    public Mono<String> queryLogistics(String orderId) {
        log.info("[MockOrderBackend] 查询物流: {}", orderId);
        return Mono.fromSupplier(() ->
                MOCK_ORDERS.containsKey(orderId)
                    ? "订单 " + orderId + " 物流：[6-11 已揽收]→[6-12 到达分拨中心]→[6-13 派送中]。"
                    : "未查询到订单 " + orderId + " 的物流信息。")
            .delayElement(Duration.ofMillis(100))
            .onErrorResume(e -> Mono.just("物流系统暂时不可用，建议稍后再试。"));
    }
}
