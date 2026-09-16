package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.capability.approval.ApprovalRequest;
import com.richard.fyoung.customerwork.capability.approval.ApprovalType;
import com.richard.fyoung.customerwork.capability.approval.PendingApprovalService;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.tool.backend.AfterSalesBackend;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/**
 * 售后/退款工具组。业务委托给可替换的 {@link AfterSalesBackend}（默认 Mock，保留资金安全红线：
 * 退款只生成待人工确认工单，不直接打款）。
 *
 * <p>注入 {@link PendingApprovalService} 后，后端成功创建退款申请才追加待人工审批单及审批单号。
 * 人工放行后的执行由已装配的审批处理器负责，本工具不执行支付。未注入审批服务时仍调用后端：
 * 真实后端创建其支持的申请，默认 Mock 返回演示内容。</p>
 * @author owlzhangfq@gmail.com
 */
public class AfterSalesTools {

    private final AfterSalesBackend backend;
    /** 可空：未注入时不登记审批单，仍调用已配置的售后后端。 */
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

    @Tool(description = "查询订单状态，并根据输入的七天标记提供退款资格参考。七天标记来自本次输入，工具不独立核实购买时间或全部退款政策。发起退款前应先调用此工具。")
    public Mono<String> checkRefundEligibility(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId,
            @ToolParam(name = "withinSevenDays", description = "该订单是否在七天无理由期内，true/false")
            String withinSevenDays,
            RuntimeContext context) {
        return withInvocationIdentity(context, () -> checkRefundEligibility(orderId, withinSevenDays));
    }

    /** 保留非原生调用入口，由调用方建立可信身份上下文。 */
    public Mono<String> checkRefundEligibility(String orderId, String withinSevenDays) {
        return backend.checkRefundEligibility(orderId, withinSevenDays);
    }

    /** 原生 Toolkit 在调用方法前切换线程，身份必须来自本轮运行快照；该上下文不进入工具参数。 */
    @Tool(description = "为订单登记待人工复核的退款申请，不执行支付。申请受理不代表退款获批或资金到账。")
    public Mono<String> submitRefund(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId,
            @ToolParam(name = "amount", description = "退款金额，单位元，例如 '299.00'")
            String amount,
            @ToolParam(name = "reason", description = "退款原因")
            String reason,
            RuntimeContext context) {
        return withInvocationIdentity(context, () -> submitRefund(orderId, amount, reason));
    }

    /** 保留非原生调用方的三参入口；审批使用入口身份快照，不依赖后端完成时所在的线程。 */
    public Mono<String> submitRefund(String orderId, String amount, String reason) {
        // 后端 Mono 可能切换线程；登记审批时必须恢复工具入口的主体和租户。
        AgentInvocationIdentity identity = AgentInvocationIdentity.capture();
        String tenantId = approvalService == null ? null : TenantContext.require();
        return backend.submitRefund(orderId, amount, reason)
            .map(result -> {
                if (approvalService == null) {
                    return result;
                }
                ApprovalRequest req = AgentInvocationIdentityContext.callWith(identity,
                    () -> TenantContext.callWith(tenantId, () -> approvalService.submit(
                        ApprovalType.REFUND, requireSessionId(), orderId, amount, reason)));
                return result + "（审批单号 " + req.getId() + "，需人工坐席放行后执行打款）";
            });
    }

    private String requireSessionId() {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("refund approval requires a real agent session");
        }
        return sessionId;
    }

    @Tool(description = "查询订单已有退款申请的审批状态。审核通过不等于支付执行或资金到账，不承诺到账时间。")
    public Mono<String> queryRefundProgress(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId,
            RuntimeContext context) {
        return withInvocationIdentity(context, () -> queryRefundProgress(orderId));
    }

    /** 保留非原生调用入口，由调用方建立可信身份上下文。 */
    public Mono<String> queryRefundProgress(String orderId) {
        return backend.queryRefundProgress(orderId);
    }

    @Tool(description = "为订单登记待人工复核的退货申请。回寄地址、运费规则与寄回期限需另行核实，本工具不会发送通知。")
    public Mono<String> submitReturn(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId,
            @ToolParam(name = "reason", description = "退货原因")
            String reason,
            RuntimeContext context) {
        return withInvocationIdentity(context, () -> submitReturn(orderId, reason));
    }

    /** 保留非原生调用入口，由调用方建立可信身份上下文。 */
    public Mono<String> submitReturn(String orderId, String reason) {
        return backend.submitReturn(orderId, reason);
    }

    @Tool(description = "为订单登记待人工复核的换货申请，记录原因及目标规格。库存、换货资格和发货安排需另行核实。")
    public Mono<String> submitExchange(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId,
            @ToolParam(name = "reason", description = "换货原因")
            String reason,
            @ToolParam(name = "newSpec", description = "要换成的规格/颜色/尺码")
            String newSpec,
            RuntimeContext context) {
        return withInvocationIdentity(context, () -> submitExchange(orderId, reason, newSpec));
    }

    /** 保留非原生调用入口，由调用方建立可信身份上下文。 */
    public Mono<String> submitExchange(String orderId, String reason, String newSpec) {
        return backend.submitExchange(orderId, reason, newSpec);
    }

    @Tool(description = "查询自有订单金额，供核实价保诉求时参考。价格变动与适用规则尚未核实，不能据此确认价保资格或补差金额。")
    public Mono<String> checkPriceProtection(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId,
            RuntimeContext context) {
        return withInvocationIdentity(context, () -> checkPriceProtection(orderId));
    }

    /** 保留非原生调用入口，由调用方建立可信身份上下文。 */
    public Mono<String> checkPriceProtection(String orderId) {
        return backend.checkPriceProtection(orderId);
    }

    @Tool(description = "为订单登记待处理的发票申请，需要发票抬头。受理申请不代表发票已开具、重开或发送。")
    public Mono<String> requestInvoice(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId,
            @ToolParam(name = "invoiceTitle", description = "发票抬头（个人姓名或公司名称）")
            String invoiceTitle,
            RuntimeContext context) {
        return withInvocationIdentity(context, () -> requestInvoice(orderId, invoiceTitle));
    }

    /** 保留非原生调用入口，由调用方建立可信身份上下文。 */
    public Mono<String> requestInvoice(String orderId, String invoiceTitle) {
        return backend.requestInvoice(orderId, invoiceTitle);
    }

    /** 后端在方法调用时捕获身份；延迟执行由后端在其工作线程恢复该快照。 */
    private Mono<String> withInvocationIdentity(RuntimeContext context, Supplier<Mono<String>> invocation) {
        AgentInvocationIdentity identity = context == null ? null : context.get(AgentInvocationIdentity.class);
        String tenantId = identity == null ? null : identity.tenantId();
        return AgentInvocationIdentityContext.callWith(identity,
            () -> TenantContext.callWith(tenantId, invocation));
    }
}
