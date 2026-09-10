package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.capability.slotfilling.SlotFillingForm;
import com.richard.fyoung.customerwork.capability.slotfilling.SlotFillingResult;
import com.richard.fyoung.customerwork.capability.slotfilling.SlotFillingService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import reactor.core.publisher.Mono;

/**
 * 退款信息收集：把已经建好却没接进主链路的槽位填充能力交给模型使用。
 *
 * <h3>它补的是什么</h3>
 * <p>槽位填充（正则抽取 + 逐项追问 + 收齐走 HITL 审批）此前<b>完整可用但没有入口</b>：
 * 唯一的调用方是 {@code /api/customer/forms/refund} 这个独立 REST 接口，
 * 而前端从未调过它，对话主链路更是完全不经过。</p>
 *
 * <h3>为什么做成工具而不是在对话链路里加分支</h3>
 * <p>在 {@code ChatDispatchService} 里判断"这是退款意图就切到表单模式"，等于给对话加一条
 * 状态机分支——用户会被<b>锁在表单里</b>，中途想问别的就答不了，而且与
 * {@code DialogStageMiddleware} 的职责重叠。做成工具则完全落在 ReAct 范式内：
 * 模型自己决定何时收集、何时切换话题，权限、审计、超时重试也都自动获得。</p>
 *
 * <h3>相对"让模型自己追问"的增量价值</h3>
 * <p>模型本来就会追问缺失信息，所以这个工具的价值不在"会问"，而在三件模型做不好的事：</p>
 * <ul>
 *   <li><b>跨轮次持久化</b>：收集进度落在 {@code cw_slot_filling_progress}，
 *       而模型靠对话历史记住——历史一被裁剪就忘了已经问过什么；</li>
 *   <li><b>格式校验</b>：订单号按正则抽取，模型不会把"上次那个单"当成订单号；</li>
 *   <li><b>不漏项</b>：必填项由表单定义保证，模型在长对话里会忘记问其中一项。</li>
 * </ul>
 *
 * @author owlzhangfq@gmail.com
 */
public class RefundIntakeTools {

    /** 工具名（{@code @Tool} 未指定 name 时框架取方法名）。对话阶段状态机按它切 COLLECTING。 */
    public static final String COLLECT_REFUND_INFO = "collectRefundInfo";

    private final SlotFillingService slotFillingService;
    private final String sessionId;

    public RefundIntakeTools(SlotFillingService slotFillingService, String sessionId) {
        this.slotFillingService = slotFillingService;
        this.sessionId = sessionId == null || sessionId.isBlank()
            ? ToolConstants.AGENT_TOOL_SESSION : sessionId;
    }

    /**
     * 逐轮收集退款所需信息。
     *
     * <p>返回文案直接面向模型：未收齐时告诉它"还缺什么、该怎么问"，收齐时把值交回去，
     * 由模型继续走 {@code checkRefundEligibility} → {@code submitRefund}。
     * 刻意不在这里直接发起退款——那会绕开资格校验这道闸。</p>
     */
    @Tool(description = "收集办理退款所需的信息（订单号、退款原因）。用户表达退款意向但信息不全时调用，"
        + "把用户这一轮的原话传进来；返回结果会告诉你还缺哪一项、该怎么追问。信息收齐后再调用退款资格校验。")
    public Mono<String> collectRefundInfo(
            @ToolParam(name = "userText", description = "用户这一轮说的原话，原样传入，不要改写")
            String userText) {
        return Mono.fromCallable(() -> {
            SlotFillingResult result = slotFillingService.submit(
                sessionId, SlotFillingForm.refundForm(), userText);
            if (!result.isComplete()) {
                return "信息尚未收齐。请向用户追问：" + result.getNextPrompt()
                    + "（已收集：" + result.getValues() + "）";
            }
            return "信息已收齐：" + result.getValues()
                + "。接下来请调用退款资格校验工具，通过后再生成退款工单。";
        });
    }
}
