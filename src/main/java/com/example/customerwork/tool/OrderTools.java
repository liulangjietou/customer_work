package com.example.customerwork.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * 订单工具组（对应流程图④"订单子 Agent" + Tool Group「订单组」）。
 *
 * <p>这些工具在生产中通常是对存量订单/物流/库存系统的封装。本示例用 Mono 模拟异步远程调用，
 * 真实落地时把方法体替换为 WebClient 调用内部微服务，或通过 MCP 协议零改造接入存量 HTTP 系统即可。</p>
 *
 * <p>关键点：工具返回 {@code Mono<String>}（异步），框架会统一为异步流式处理；多个查询类工具
 * 可由 Agent 并行调用并聚合结果，缓解上下文与时延压力。</p>
 * @author owlzhangfq@gmail.com
 */
public class OrderTools {

    private static final Logger log = LoggerFactory.getLogger(OrderTools.class);

    // 模拟数据库：生产中替换为对内部订单服务的调用
    private static final Map<String, Map<String, String>> MOCK_ORDERS = Map.of(
        "20260613001", Map.of("status", "已发货", "amount", "299.00", "createTime", "2026-06-10",
                              "payable", "true", "withinSevenDays", "true"),
        "20260613002", Map.of("status", "已签收", "amount", "1599.00", "createTime", "2026-05-20",
                              "payable", "false", "withinSevenDays", "false")
    );

    @Tool(description = "根据订单号查询订单状态、金额、创建时间等基础信息。当用户询问订单进度、物流、金额时调用。")
    public Mono<String> queryOrder(
            @ToolParam(name = "orderId", description = "订单号，例如 '20260613001'")
            String orderId) {
        log.info("[OrderTools] 查询订单: {}", orderId);
        return Mono.fromSupplier(() -> {
                Map<String, String> order = MOCK_ORDERS.get(orderId);
                if (order == null) {
                    return "未查询到订单 " + orderId + "，请用户核对订单号。";
                }
                return String.format("订单 %s：状态=%s，金额=%s 元，下单时间=%s。",
                    orderId, order.get("status"), order.get("amount"), order.get("createTime"));
            })
            // 模拟网络耗时，禁止用 Thread.sleep，使用 delayElement
            .delayElement(Duration.ofMillis(120))
            .onErrorResume(e -> {
                log.error("[OrderTools] 查询订单失败: {}", orderId, e);
                return Mono.just("订单系统暂时不可用，建议稍后再试或转人工。");
            });
    }

    @Tool(description = "查询订单的物流轨迹。当用户询问'我的快递到哪了'之类的问题时调用。")
    public Mono<String> queryLogistics(
            @ToolParam(name = "orderId", description = "订单号，例如 '20260613001'")
            String orderId) {
        log.info("[OrderTools] 查询物流: {}", orderId);
        return Mono.fromSupplier(() ->
                MOCK_ORDERS.containsKey(orderId)
                    ? "订单 " + orderId + " 物流：[6-11 已揽收]→[6-12 到达分拨中心]→[6-13 派送中]。"
                    : "未查询到订单 " + orderId + " 的物流信息。")
            .delayElement(Duration.ofMillis(100))
            .onErrorResume(e -> {
                log.error("[OrderTools] 查询物流失败: {}", orderId, e);
                return Mono.just("物流系统暂时不可用，建议稍后再试。");
            });
    }
}
