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
}
