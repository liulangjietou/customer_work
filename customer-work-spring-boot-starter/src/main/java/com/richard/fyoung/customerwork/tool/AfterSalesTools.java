package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.tool.backend.AfterSalesBackend;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import reactor.core.publisher.Mono;

/**
 * 售后/退款工具组。业务委托给可替换的 {@link AfterSalesBackend}（默认 Mock，保留资金安全红线：
 * 退款只生成待人工确认工单，不直接打款）。
 * @author owlzhangfq@gmail.com
 */
public class AfterSalesTools {

    private final AfterSalesBackend backend;

    public AfterSalesTools(AfterSalesBackend backend) {
        this.backend = backend;
    }

    @Tool(description = "校验某订单是否满足退款条件（是否在七天无理由期内、是否已支付）。发起退款前必须先调用此工具。")
    public Mono<String> checkRefundEligibility(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId,
            @ToolParam(name = "withinSevenDays", description = "该订单是否在七天无理由期内，true/false")
            String withinSevenDays) {
        return backend.checkRefundEligibility(orderId, withinSevenDays);
    }

    @Tool(description = "对满足条件的订单发起退款。注意：本工具只生成待人工确认的退款工单，不会直接打款，需人工坐席复核后执行。")
    public Mono<String> submitRefund(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId,
            @ToolParam(name = "amount", description = "退款金额，单位元，例如 '299.00'")
            String amount,
            @ToolParam(name = "reason", description = "退款原因")
            String reason) {
        return backend.submitRefund(orderId, amount, reason);
    }
}
