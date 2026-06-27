package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.approval.ApprovalRequest;
import com.richard.fyoung.customerwork.approval.ApprovalType;
import com.richard.fyoung.customerwork.approval.PendingApprovalService;
import com.richard.fyoung.customerwork.tool.backend.AfterSalesBackend;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import reactor.core.publisher.Mono;

/**
 * 售后/退款工具组。业务委托给可替换的 {@link AfterSalesBackend}（默认 Mock，保留资金安全红线：
 * 退款只生成待人工确认工单，不直接打款）。
 *
 * <p>注入 {@link PendingApprovalService} 后，退款工单会登记为待人工审批单并附带审批单号，
 * 由人工坐席经 approve 端点放行后才执行打款（Human-in-the-Loop 闭环）。未注入时退化为仅生成工单文案。</p>
 * @author owlzhangfq@gmail.com
 */
public class AfterSalesTools {

    private static final String TOOL_SESSION = "agent-tool";

    private final AfterSalesBackend backend;
    /** 可空：未注入时不登记审批单，保持纯工具可用。 */
    private final PendingApprovalService approvalService;

    public AfterSalesTools(AfterSalesBackend backend) {
        this(backend, null);
    }

    public AfterSalesTools(AfterSalesBackend backend, PendingApprovalService approvalService) {
        this.backend = backend;
        this.approvalService = approvalService;
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
        return backend.submitRefund(orderId, amount, reason)
            .map(result -> {
                if (approvalService == null) {
                    return result;
                }
                ApprovalRequest req = approvalService.submit(
                    ApprovalType.REFUND, TOOL_SESSION, orderId, amount, reason);
                return result + "（审批单号 " + req.getId() + "，需人工坐席放行后执行打款）";
            });
    }
}
