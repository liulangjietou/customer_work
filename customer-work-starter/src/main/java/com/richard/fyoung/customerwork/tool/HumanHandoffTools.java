package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.capability.handoff.HandoffTicket;
import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
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
 * <p>注入协作方后，转人工形成双写闭环：</p>
 * <ul>
 *   <li>{@link TicketService}（有真实 {@code sessionId} 时）：把当前会话的活跃工单推进到
 *       {@code WAITING_AGENT} 排队接单——工单域承载完整生命周期状态机；</li>
 *   <li>{@link HandoffService}：登记一张 {@link HandoffTicket}（PENDING）供坐席工作台接单处理。</li>
 * </ul>
 *
 * <p>工单域驱动为尽力而为：其失败只 error 日志、不阻断话术返回（转人工的首要目标是给用户一个明确答复）。
 * 未注入任何协作方时退化为仅生成话术文案，保持纯工具可用。</p>
 * @author owlzhangfq@gmail.com
 */
public class HumanHandoffTools {

    private static final Logger log = LoggerFactory.getLogger(HumanHandoffTools.class);

    /** 无真实会话上下文时的占位会话（沿用历史语义，保证纯工具可用）。 */

    /** 可空：未注入时不登记 handoff 工单，保持纯工具可用。 */
    private final HandoffService handoffService;
    /** 可空：未注入 / 无真实会话时不驱动工单域。 */
    private final TicketService ticketService;
    /** 可空：真实会话标识（有则用于精确关联工单域并作为 handoff 会话号）。 */
    private final String sessionId;

    public HumanHandoffTools() {
        this(null, null, null);
    }

    public HumanHandoffTools(HandoffService handoffService) {
        this(handoffService, null, null);
    }

    public HumanHandoffTools(HandoffService handoffService, TicketService ticketService, String sessionId) {
        this.handoffService = handoffService;
        this.ticketService = ticketService;
        this.sessionId = sessionId;
    }

    @Tool(description = "将当前会话升级转接到人工坐席。当用户情绪激动、明确要求人工、或涉及高风险/大额资金/投诉升级时调用。")
    public Mono<String> transferToHuman(
            @ToolParam(name = "reason", description = "转人工的原因，例如 '用户投诉升级' '涉及大额退款'")
            String reason) {
        return Mono.fromSupplier(() -> {
                driveTicketDomain(reason);
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

    /** 尽力而为地把工单域活跃工单推进到 WAITING_AGENT；失败只 error 日志、不阻断话术返回。 */
    private void driveTicketDomain(String reason) {
        if (sessionId == null || sessionId.isBlank() || ticketService == null) {
            return;
        }
        try {
            ticketService.requestHandoff(sessionId, reason, TicketActorType.BOT, null);
        } catch (Exception e) {
            log.error("[HumanHandoffTools] ticket handoff drive failed, code={}, session={}",
                "TICKET-HANDOFF-DRIVE-FAIL", sessionId, e);
        }
    }

    private String replyText(String ticketId, String reason) {
        return String.format(
            "已为您转接人工坐席（工单号 %s，原因：%s）。"
          + "您的对话记录已完整同步给坐席，无需重复描述，请稍候。",
            ticketId, reason);
    }
}
