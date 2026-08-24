package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.capability.approval.ApprovalRequest;
import com.richard.fyoung.customerwork.capability.approval.ApprovalType;
import com.richard.fyoung.customerwork.capability.approval.PendingApprovalService;
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

    private final AfterSalesBackend backend;
    /** 可空：未注入时不登记审批单，保持纯工具可用。 */
    private final PendingApprovalService approvalService;
    /** 构建 Agent 时冻结的真实会话；非 Agent 工具场景可为 null。 */
    private final String sessionId;

    public AfterSalesTools(AfterSalesBackend backend) {
        this(backend, null, null);
    }

    public AfterSalesTools(AfterSalesBackend backend, PendingApprovalService approvalService) {
        this(backend, approvalService, null);
    }

    public AfterSalesTools(AfterSalesBackend backend, PendingApprovalService approvalService,
                           String sessionId) {
        this.backend = backend;
        this.approvalService = approvalService;
        this.sessionId = sessionId;
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
                    ApprovalType.REFUND, requireSessionId(), orderId, amount, reason);
                return result + "（审批单号 " + req.getId() + "，需人工坐席放行后执行打款）";
            });
    }

    private String requireSessionId() {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("refund approval requires a real agent session");
        }
        return sessionId;
    }

    @Tool(description = "查询某订单的退款/退货处理进度。用户问'退款到哪了''退款进度''钱什么时候到'时调用。")
    public Mono<String> queryRefundProgress(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId) {
        return backend.queryRefundProgress(orderId);
    }

    @Tool(description = "对订单发起退货申请，生成退货工单并告知回寄流程。用户明确要求退货（退款并寄回商品）时调用。")
    public Mono<String> submitReturn(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId,
            @ToolParam(name = "reason", description = "退货原因")
            String reason) {
        return backend.submitReturn(orderId, reason);
    }

    @Tool(description = "对订单发起换货申请（换规格/颜色/尺码等）。用户要求换货而非退款时调用。")
    public Mono<String> submitExchange(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId,
            @ToolParam(name = "reason", description = "换货原因")
            String reason,
            @ToolParam(name = "newSpec", description = "要换成的规格/颜色/尺码")
            String newSpec) {
        return backend.submitExchange(orderId, reason, newSpec);
    }

    @Tool(description = "校验并申请价保（买后降价补差）。用户说'买完降价了''能退差价吗''价保'时调用。")
    public Mono<String> checkPriceProtection(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId) {
        return backend.checkPriceProtection(orderId);
    }

    @Tool(description = "为订单申请或重开发票。用户要求开发票/重开发票时调用，需提供发票抬头。")
    public Mono<String> requestInvoice(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId,
            @ToolParam(name = "invoiceTitle", description = "发票抬头（个人姓名或公司名称）")
            String invoiceTitle) {
        return backend.requestInvoice(orderId, invoiceTitle);
    }
}
