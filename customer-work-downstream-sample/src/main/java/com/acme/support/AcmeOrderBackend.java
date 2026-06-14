package com.acme.support;

import com.richard.fyoung.customerwork.tool.backend.OrderBackend;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 下游自定义订单后端：演示"声明同类型 Bean 即覆盖 starter 默认 Mock"。
 *
 * <p>本 Bean 一旦存在，starter 中以 {@code @ConditionalOnMissingBean} 注册的 {@code MockOrderBackend}
 * 自动让位，Agent 的订单工具便调用到你这里——接你真实订单系统就把方法体换成 RPC/WebClient 即可。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class AcmeOrderBackend implements OrderBackend {

    @Override
    public Mono<String> queryOrder(String orderId) {
        // demo：真实环境改为调用 ACME 订单微服务
        return Mono.just("【ACME 订单系统】订单 " + orderId + "：状态=已发货，金额=￥199.00");
    }

    @Override
    public Mono<String> queryLogistics(String orderId) {
        return Mono.just("【ACME 物流】订单 " + orderId + "：[已揽收]→[运输中]→[派送中]");
    }
}
