package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerworkapp.web.HttpErrors;
import com.richard.fyoung.customerworkapp.web.ApiRequestTenant;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.capability.approval.ApprovalRequest;
import com.richard.fyoung.customerwork.capability.approval.ApprovalStatus;
import com.richard.fyoung.customerwork.capability.approval.PendingApprovalService;
import com.richard.fyoung.customerwork.observability.AuditSink;
import com.richard.fyoung.customerwork.safety.security.ApprovalAuthWebFilter;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工审批端点（Human-in-the-Loop 闭环）：退款等高风险动作生成待审单，人工坐席经此 approve/deny 放行。
 *
 * <p>与框架 Permission ASK（工具调用层闸门）互补：Permission 把关"是否允许 Agent 调用退款工具"，
 * 本端点把关"工单生成后是否真打款"，形成 <b>挂起 → 人工决策 → 生效</b> 的可观测闭环。</p>
 *
 * <p><b>操作员身份来源</b>：开启 {@code security.approval-auth.enabled} 后，操作员身份由
 * {@link ApprovalAuthWebFilter} 按 token 解析后写入请求属性，本类只信任该属性，{@code operator}
 * 请求参数被忽略——避免调用方自报任意身份放行退款。未开启时（本地开发）退化为信任
 * {@code operator} 参数，行为与旧版本一致。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/customer/approvals")
@Tag(name = "人工审批", description = "待审单查询 / 放行 / 拒绝（退款等资金动作的人工闭环）")
public class ApprovalController {

    private static final String DEFAULT_OPERATOR = "agent-console";
    private static final String AUDIT_TYPE_APPROVAL_DECISION = "approval-decision";

    private final PendingApprovalService approvalService;
    private final AuditSink auditSink;
    private final ApiRequestTenant requestTenant;

    /** 无 Spring 构造保留本地匿名模式；运行时使用注入的实际配置。 */
    public ApprovalController(PendingApprovalService approvalService, AuditSink auditSink) {
        this(approvalService, auditSink, new ApiRequestTenant(new CustomerWorkProperties()));
    }

    @Autowired
    public ApprovalController(PendingApprovalService approvalService, AuditSink auditSink,
                              ApiRequestTenant requestTenant) {
        this.approvalService = approvalService;
        this.auditSink = auditSink;
        this.requestTenant = requestTenant;
    }

    @Operation(summary = "审批单列表", description = "可选 ?status=pending/approved/denied 过滤")
    @GetMapping
    public Mono<List<ApprovalRequest>> list(@RequestParam(required = false) String status,
                                            ServerWebExchange exchange) {
        String tenantId = requestTenant.require(exchange);
        ApprovalStatus filter = StringUtils.hasText(status) ? parseStatus(status) : null;
        return Mono.fromCallable(() -> TenantContext.callWith(tenantId, () -> filter == null
                ? approvalService.list() : approvalService.listByStatus(filter)))
            .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "审批单详情")
    @GetMapping("/{id}")
    public Mono<ApprovalRequest> get(@PathVariable String id, ServerWebExchange exchange) {
        String tenantId = requestTenant.require(exchange);
        return Mono.fromCallable(() -> TenantContext.callWith(tenantId, () -> approvalService.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "approval not found: " + id))))
            .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "放行审批单", description = "人工坐席放行，触发下游执行（如打款）")
    @PostMapping("/{id}/approve")
    public Mono<ApprovalRequest> approve(@PathVariable String id,
                                         @RequestParam(defaultValue = DEFAULT_OPERATOR) String operator,
                                         ServerWebExchange exchange) {
        String tenantId = requestTenant.require(exchange);
        String resolvedOperator = resolveOperator(exchange, operator);
        return Mono.fromCallable(() -> TenantContext.callWith(tenantId, () -> {
                ApprovalRequest req = approvalService.approve(id, resolvedOperator);
                audit(req, "approve", resolvedOperator, null);
                return req;
            }))
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorMap(HttpErrors::translate);
    }

    @Operation(summary = "拒绝审批单")
    @PostMapping("/{id}/deny")
    public Mono<ApprovalRequest> deny(@PathVariable String id,
                                      @RequestParam(defaultValue = DEFAULT_OPERATOR) String operator,
                                      @RequestParam(required = false) String note,
                                      ServerWebExchange exchange) {
        String tenantId = requestTenant.require(exchange);
        String resolvedOperator = resolveOperator(exchange, operator);
        return Mono.fromCallable(() -> TenantContext.callWith(tenantId, () -> {
                ApprovalRequest req = approvalService.deny(id, resolvedOperator, note);
                audit(req, "deny", resolvedOperator, note);
                return req;
            }))
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorMap(HttpErrors::translate);
    }

    /**
     * 操作员身份解析：{@link ApprovalAuthWebFilter} 已解析并写入请求属性时以其为准（唯一可信来源）；
     * 未写入（鉴权未开启）时退化为信任请求参数，仅供本地开发使用。
     */
    private String resolveOperator(ServerWebExchange exchange, String fallbackOperator) {
        String resolved = exchange.getAttribute(ApprovalAuthWebFilter.RESOLVED_OPERATOR_ATTR);
        return resolved != null ? resolved : fallbackOperator;
    }

    /** 审批决策审计留痕（合规追溯：谁在何时对哪张工单做了何种决策）。 */
    private void audit(ApprovalRequest req, String decision, String operator, String note) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("approvalId", req.getId());
        fields.put("type", req.getType());
        fields.put("orderId", req.getOrderId());
        fields.put("sessionId", req.getSessionId());
        fields.put("decision", decision);
        fields.put("operator", operator);
        if (note != null) {
            fields.put("note", note);
        }
        auditSink.record(AUDIT_TYPE_APPROVAL_DECISION, fields);
    }

    private ApprovalStatus parseStatus(String status) {
        try {
            return ApprovalStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status: " + status);
        }
    }
}
