package com.richard.fyoung.customerwork.tool.backend;

import reactor.core.publisher.Mono;

/**
 * 订单后端（扩展点）：对接你自己的订单 / 物流 / 库存系统。
 *
 * <p>默认提供 {@link MockOrderBackend} 演示实现（标注 {@code @ConditionalOnMissingBean}）。
 * 接入真实系统时，只需提供一个实现本接口的 {@code @Bean}（用 WebClient / RPC 调你的微服务），
 * 即可自动覆盖默认实现，<b>无需改动框架代码</b>。</p>
 * @author owlzhangfq@gmail.com
 */
public interface OrderBackend {

    /** 查询订单状态、金额、创建时间等基础信息。 */
    Mono<String> queryOrder(String orderId);

    /** 查询订单物流轨迹。 */
    Mono<String> queryLogistics(String orderId);

    /**
     * 售中：修改收货地址。接口以 {@code default} 演进——老实现无需改动即兜底为"转人工"，
     * 自定义后端可按需 override 对接真实订单系统。
     */
    default Mono<String> modifyAddress(String orderId, String newAddress) {
        return Mono.just("当前订单暂不支持自助改址，已为您转人工坐席处理。");
    }

    /** 售中：取消订单（default 兜底转人工）。 */
    default Mono<String> cancelOrder(String orderId, String reason) {
        return Mono.just("当前订单暂不支持自助取消，已为您转人工坐席处理。");
    }

    /** 售中：催发货（default 兜底登记，由人工跟进）。 */
    default Mono<String> urgeShipment(String orderId) {
        return Mono.just("已记录您的催发货诉求，稍后由人工坐席跟进。");
    }
}
