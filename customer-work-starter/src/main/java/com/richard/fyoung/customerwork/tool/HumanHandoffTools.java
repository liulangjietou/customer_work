package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.capability.handoff.HandoffTicket;
import com.richard.fyoung.customerwork.data.ticket.TicketService;
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
 * <p>注入 {@link HandoffService} 后只调用一次权威工单状态机：HandoffService 将 {@code cw_ticket}
 * 推进到 WAITING_AGENT，并把结果投影为兼容的 {@link HandoffTicket}。不再同时写
 * {@code cw_handoff_ticket}，因此工具回复的工单号就是后续接单、SLA 与结案共同使用的 TK 工单号。</p>
 * @author owlzhangfq@gmail.com
 */
public class HumanHandoffTools {

    private static final Logger log = LoggerFactory.getLogger(HumanHandoffTools.class);

    /** 可空：未注入时不登记 handoff 工单，保持纯工具可用。 */
    private final HandoffService handoffService;
    /** 可空：真实会话标识（有则用于精确关联工单域并作为 handoff 会话号）。 */
    private final String sessionId;

    public HumanHandoffTools() {
        this(null, null, null);
    }

    public HumanHandoffTools(HandoffService handoffService) {
        this(handoffService, null, null);
    }

    public HumanHandoffTools(HandoffService handoffService, TicketService ignoredTicketService, String sessionId) {
        this.handoffService = handoffService;
        this.sessionId = sessionId;
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
                String session = (sessionId != null && !sessionId.isBlank())
                    ? sessionId : ToolConstants.AGENT_TOOL_SESSION;
                HandoffTicket ticket = handoffService.create(session, reason);
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
