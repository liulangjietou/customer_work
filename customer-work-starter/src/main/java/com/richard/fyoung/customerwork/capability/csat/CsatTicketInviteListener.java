package com.richard.fyoung.customerwork.capability.csat;

import com.richard.fyoung.customerwork.data.ticket.Ticket;
import com.richard.fyoung.customerwork.data.ticket.TicketEvent;
import com.richard.fyoung.customerwork.data.ticket.TicketEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工单进入终态时发出满意度邀请。
 *
 * <p><b>补的是一条断掉的链路</b>：邀请此前只挂在 {@code CustomerServiceService#endSession} 上，
 * 而用户端真正的结束动作走的是工单状态机（关单 / 确认解决），压根不经过那里——
 * 于是只有"空闲超时清理"才会发出邀请，那时用户早已离开、评分卡也无从弹出。
 * 结果是分母长期为 0，看板三个指标全是 0.0%，而链路本身看不出任何异常。</p>
 *
 * <p><b>为什么挂在事件上而不是塞进 {@code TicketService}</b>：工单领域不该知道满意度的存在。
 * 事件扩展点已是既有模式（同 {@code WsTicketEventListener}），JDBC 模式下经 Outbox
 * 至少投递一次——进程崩溃也不会漏掉邀请，这对"分母必须完整"的回收率恰恰重要。
 * 代价是 1~2 秒投递延迟（Outbox 扫描间隔默认 1s），用户端评分卡因此会重试一次拉取状态。</p>
 *
 * <p>重复投递由 {@link CsatService#invite} 自身幂等挡住，无需再按事件 ID 去重。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public class CsatTicketInviteListener implements TicketEventListener {

    private static final Logger log = LoggerFactory.getLogger(CsatTicketInviteListener.class);

    private final CsatService csatService;

    public CsatTicketInviteListener(CsatService csatService) {
        this.csatService = csatService;
    }

    @Override
    public void onTicketEvent(Ticket ticket, TicketEvent event) {
        // 只认落在终态的流转；非终态流转（转人工、接单、挂起等）与本指标无关
        if (event.toStatus() == null || !event.toStatus().isEnded()) {
            return;
        }
        try {
            csatService.invite(ticket.getSessionId());
        } catch (Exception e) {
            // 满意度是旁路指标，邀请失败不该影响工单流转与下游监听器
            log.error("csat invite on ticket end failed, code={}, ticketId={}, sessionId={}",
                "CSAT-TICKET-INVITE-FAIL", ticket.getId(), ticket.getSessionId(), e);
        }
    }
}
