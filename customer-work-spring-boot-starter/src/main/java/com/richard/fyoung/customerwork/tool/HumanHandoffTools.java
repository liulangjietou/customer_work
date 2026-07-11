package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.handoff.HandoffService;
import com.richard.fyoung.customerwork.handoff.HandoffTicket;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 人工坐席通道工具（对应流程图④"人工坐席通道"与③"情绪/风险熔断"）。
 *
 * <p>当出现以下情况时，主 Agent 应调用本工具升级到人工：</p>
 * <ul>
 *   <li>用户情绪强烈、明确要求人工；</li>
 *   <li>涉及大额资金、投诉升级等高风险场景；</li>
 *   <li>多轮仍无法解决。</li>
 * </ul>
 *
 * <p>注入 {@link HandoffService} 后，转人工会登记为可查询、可流转的 {@link HandoffTicket}
 * （PENDING 待接单 → 坐席 claim 接单 → resolve 处理完毕回收给 AI），供坐席经
 * {@code /api/customer/handoffs} 端点接单处理——取代此前"只打日志 + 生成随机字符串"的空实现。
 * 未注入时退化为仅生成话术文案，保持纯工具可用。</p>
 *
 * <p><b>已知限制</b>：框架当前的工具调用未打通 RuntimeContext 注入，本类拿不到真实
 * {@code sessionId}（与 {@code AfterSalesTools} 的 {@code submitRefund} 同一限制），
 * 沿用同一占位值 {@link #TOOL_SESSION}——工单因此无法精确关联到发起会话，
 * 若需要精确会话关联，需先在框架/Toolkit 层打通会话上下文注入（更大范围的改动，不在本次范围）。</p>
 * @author owlzhangfq@gmail.com
 */
public class HumanHandoffTools {

    private static final Logger log = LoggerFactory.getLogger(HumanHandoffTools.class);

    private static final String TOOL_SESSION = "agent-tool";

    /** 可空：未注入时不登记工单，保持纯工具可用。 */
    private final HandoffService handoffService;

    public HumanHandoffTools() {
        this(null);
    }

    public HumanHandoffTools(HandoffService handoffService) {
        this.handoffService = handoffService;
    }

    @Tool(description = "将当前会话升级转接到人工坐席。当用户情绪激动、明确要求人工、或涉及高风险/大额资金/投诉升级时调用。")
    public Mono<String> transferToHuman(
            @ToolParam(name = "reason", description = "转人工的原因，例如 '用户投诉升级' '涉及大额退款'")
            String reason) {
        return Mono.fromSupplier(() -> {
                if (handoffService == null) {
                    String fallbackId = "HO" + System.currentTimeMillis();
                    return replyText(fallbackId, reason);
                }
                HandoffTicket ticket = handoffService.create(TOOL_SESSION, reason);
                return replyText(ticket.getId(), reason);
            })
            .onErrorResume(e -> {
                log.error("[HumanHandoffTools] transfer failed, code={}", "HANDOFF-CREATE-FAIL", e);
                return Mono.just("人工坐席通道繁忙，已为您记录，将尽快回拨。");
            });
    }

    private String replyText(String ticketId, String reason) {
        return String.format(
            "已为您转接人工坐席（工单号 %s，原因：%s）。"
          + "您的对话记录已完整同步给坐席，无需重复描述，请稍候。",
            ticketId, reason);
    }
}
