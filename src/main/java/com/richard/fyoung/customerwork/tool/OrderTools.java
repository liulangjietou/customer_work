package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.tool.backend.OrderBackend;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import reactor.core.publisher.Mono;

/**
 * 订单工具组（Tool Group「订单组」）。
 *
 * <p>本类是暴露给 LLM 的工具 Schema 壳，业务实现委托给可替换的 {@link OrderBackend}：
 * 默认走内存 Mock，生产提供自定义 {@code OrderBackend} Bean 即接入真实订单 / 物流系统。</p>
 * @author owlzhangfq@gmail.com
 */
public class OrderTools {

    private final OrderBackend backend;

    public OrderTools(OrderBackend backend) {
        this.backend = backend;
    }

    @Tool(description = "根据订单号查询订单状态、金额、创建时间等基础信息。当用户询问订单进度、物流、金额时调用。")
    public Mono<String> queryOrder(
            @ToolParam(name = "orderId", description = "订单号，例如 '20260613001'")
            String orderId) {
        return backend.queryOrder(orderId);
    }

    @Tool(description = "查询订单的物流轨迹。当用户询问'我的快递到哪了'之类的问题时调用。")
    public Mono<String> queryLogistics(
            @ToolParam(name = "orderId", description = "订单号，例如 '20260613001'")
            String orderId) {
        return backend.queryLogistics(orderId);
    }
}
