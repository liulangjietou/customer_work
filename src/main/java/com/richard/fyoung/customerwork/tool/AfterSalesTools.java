package com.richard.fyoung.customerwork.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 售后/退款工具组（对应流程图④"售后/退款子 Agent"）。
 *
 * <p>体现两个生产关键点：</p>
 * <ol>
 *   <li><b>规则校验</b>：退款前先校验"七天无理由""是否已支付"等业务规则；</li>
 *   <li><b>涉资金操作不自动执行</b>：真正的退款落账动作只生成一张"待人工确认工单"，
 *       由人工坐席确认后再执行（Human-in-the-loop）。工具本身不直接打钱，
 *       这是资金安全的兜底设计。</li>
 * </ol>
 * @author owlzhangfq@gmail.com
 */
public class AfterSalesTools {

    private static final Logger log = LoggerFactory.getLogger(AfterSalesTools.class);

    @Tool(description = "校验某订单是否满足退款条件（是否在七天无理由期内、是否已支付）。发起退款前必须先调用此工具。")
    public Mono<String> checkRefundEligibility(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId,
            @ToolParam(name = "withinSevenDays", description = "该订单是否在七天无理由期内，true/false")
            String withinSevenDays) {
        log.info("[AfterSalesTools] 退款资格校验: order={}, within7={}", orderId, withinSevenDays);
        return Mono.fromSupplier(() -> {
                if (!"true".equalsIgnoreCase(withinSevenDays)) {
                    return "订单 " + orderId + " 已超出七天无理由期，不满足无理由退款条件，"
                         + "如确需退款请走特殊申诉流程（转人工）。";
                }
                return "订单 " + orderId + " 满足七天无理由退款条件，可发起退款申请。";
            })
            .delayElement(Duration.ofMillis(80))
            .onErrorResume(e -> {
                log.error("[AfterSalesTools] 退款资格校验失败: {}", orderId, e);
                return Mono.just("退款规则引擎暂时不可用，建议转人工处理。");
            });
    }

    @Tool(description = "对满足条件的订单发起退款。注意：本工具只生成待人工确认的退款工单，不会直接打款，需人工坐席复核后执行。")
    public Mono<String> submitRefund(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId,
            @ToolParam(name = "amount", description = "退款金额，单位元，例如 '299.00'")
            String amount,
            @ToolParam(name = "reason", description = "退款原因")
            String reason) {
        log.info("[AfterSalesTools] 生成退款工单: order={}, amount={}, reason={}", orderId, amount, reason);
        return Mono.fromSupplier(() -> {
                // 注意：这里不真正打款，只落一张待人工确认工单
                String ticketId = "RF" + System.currentTimeMillis();
                return String.format(
                    "已生成退款工单 %s：订单=%s，金额=%s 元，原因=%s。"
                  + "【涉及资金，已转人工坐席复核，预计 1 个工作日内处理完成】",
                    ticketId, orderId, amount, reason);
            })
            .delayElement(Duration.ofMillis(90))
            .onErrorResume(e -> {
                log.error("[AfterSalesTools] 生成退款工单失败: {}", orderId, e);
                return Mono.just("退款工单系统暂时不可用，已为您转接人工坐席。");
            });
    }
}
