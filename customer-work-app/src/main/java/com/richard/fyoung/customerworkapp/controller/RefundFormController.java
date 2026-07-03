package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.approval.ApprovalRequest;
import com.richard.fyoung.customerwork.approval.ApprovalType;
import com.richard.fyoung.customerwork.approval.PendingApprovalService;
import com.richard.fyoung.customerwork.dto.ChatRequest;
import com.richard.fyoung.customerwork.slotfilling.SlotFillingForm;
import com.richard.fyoung.customerwork.slotfilling.SlotFillingResult;
import com.richard.fyoung.customerwork.slotfilling.SlotFillingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * 退款多轮信息收集端点（借鉴 AliGo「事项收集智能体」）：逐轮收集 订单号 + 退款原因，
 * 收齐后<b>串接人工审批闭环</b>——生成待审退款单（{@link PendingApprovalService}），人工放行后执行打款。
 *
 * <p>同一 {@code sessionId} 连续 POST 推进同一张表单；返回 {@code complete=false} 时按 {@code nextPrompt}
 * 继续追问，{@code complete=true} 时附带审批单号。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/customer/forms/refund")
@Tag(name = "退款信息收集", description = "多轮槽位收集 + 收齐后生成待人工审批退款单")
public class RefundFormController {

    private final SlotFillingService slotFillingService;
    private final PendingApprovalService approvalService;

    public RefundFormController(SlotFillingService slotFillingService,
                               PendingApprovalService approvalService) {
        this.slotFillingService = slotFillingService;
        this.approvalService = approvalService;
    }

    @Operation(summary = "提交一轮退款信息", description = "多轮收集订单号/原因，收齐后生成待审退款单")
    @PostMapping
    public Mono<Map<String, Object>> collect(@Valid @RequestBody ChatRequest request) {
        String sessionId = StringUtils.hasText(request.sessionId())
            ? request.sessionId() : "refund-" + UUID.randomUUID();
        SlotFillingResult result = slotFillingService.submit(
            sessionId, SlotFillingForm.refundForm(), request.message());

        if (!result.isComplete()) {
            return Mono.just(Map.of(
                "sessionId", sessionId,
                "complete", false,
                "nextPrompt", result.getNextPrompt(),
                "collected", result.getValues()));
        }
        // 收齐 → 生成待人工审批退款单（串接 HITL 闭环）
        ApprovalRequest approval = approvalService.submit(
            ApprovalType.REFUND, sessionId,
            result.getValues().get("orderId"), null, result.getValues().get("reason"));
        return Mono.just(Map.of(
            "sessionId", sessionId,
            "complete", true,
            "collected", result.getValues(),
            "approvalId", approval.getId(),
            "message", "信息已收齐，已生成待人工审批退款单 " + approval.getId() + "，人工坐席放行后执行打款。"));
    }
}
