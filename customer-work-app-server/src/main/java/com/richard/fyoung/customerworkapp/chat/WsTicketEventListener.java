package com.richard.fyoung.customerworkapp.chat;

import com.richard.fyoung.customerwork.data.ticket.Ticket;
import com.richard.fyoung.customerwork.data.ticket.TicketEvent;
import com.richard.fyoung.customerwork.data.ticket.TicketEventListener;
import com.richard.fyoung.customerwork.data.ticket.TicketEventType;
import com.richard.fyoung.customerwork.infra.ws.WsFrame;
import com.richard.fyoung.customerwork.infra.ws.WsSessionRegistry;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工单事件 → WebSocket 实时推送监听器（注册为 Bean，被 starter {@code TicketService} 自动织入广播）。
 *
 * <p>每次工单流转：向工单归属用户与当前坐席各推一帧 {@code ticket_event}（前端据此实时刷新工单卡片）；
 * 其中"请求转人工 / 重新打开"意味着一张单进入排队待抢，额外向全部在线坐席广播 {@code ticket_new} 唤起抢单。</p>
 *
 * <p>本监听器在 {@code TicketService.broadcast} 内被同步回调，推送走非阻塞 Sink（{@code tryEmitNext}），
 * 不阻塞触发流转的线程；监听器自身异常由 {@code TicketService} 兜住，不影响主流转。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class WsTicketEventListener implements TicketEventListener {

    private static final String KEY_TICKET_ID = "ticketId";
    private static final String KEY_USER_ID = "userId";
    private static final String KEY_TITLE = "title";
    private static final String KEY_PRIORITY = "priority";
    private static final String KEY_CATEGORY = "category";
    private static final String KEY_STATUS = "status";
    private static final String KEY_EVENT_TYPE = "eventType";
    private static final String KEY_FROM_STATUS = "fromStatus";
    private static final String KEY_TO_STATUS = "toStatus";
    private static final String KEY_ACTOR_TYPE = "actorType";
    private static final String KEY_ASSIGNEE = "assignee";
    private static final String KEY_TS = "ts";

    private final WsSessionRegistry registry;

    public WsTicketEventListener(WsSessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onTicketEvent(Ticket ticket, TicketEvent event) {
        WsFrame eventFrame = WsFrame.ticketEvent(eventData(ticket, event));
        registry.pushToUser(ticket.getUserId(), eventFrame);
        if (ticket.getAssignee() != null) {
            registry.pushToAgent(ticket.getAssignee(), eventFrame);
        }
        // 进入排队待抢单：唤起全部在线坐席
        if (event.eventType() == TicketEventType.REQUEST_HANDOFF
            || event.eventType() == TicketEventType.REOPEN) {
            registry.broadcastToAgents(WsFrame.ticketNew(newTicketData(ticket)));
        }
    }

    private Map<String, Object> eventData(Ticket ticket, TicketEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(KEY_TICKET_ID, ticket.getId());
        data.put(KEY_EVENT_TYPE, event.eventType().name());
        data.put(KEY_FROM_STATUS, event.fromStatus() == null ? null : event.fromStatus().name());
        data.put(KEY_TO_STATUS, event.toStatus() == null ? null : event.toStatus().name());
        data.put(KEY_ACTOR_TYPE, event.actorType() == null ? null : event.actorType().name());
        data.put(KEY_STATUS, ticket.getStatus().name());
        data.put(KEY_ASSIGNEE, ticket.getAssignee());
        data.put(KEY_TS, event.createdAtMs());
        return data;
    }

    private Map<String, Object> newTicketData(Ticket ticket) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(KEY_TICKET_ID, ticket.getId());
        data.put(KEY_USER_ID, ticket.getUserId());
        data.put(KEY_TITLE, ticket.getTitle());
        data.put(KEY_PRIORITY, ticket.getPriority().name());
        data.put(KEY_CATEGORY, ticket.getCategory().name());
        data.put(KEY_TS, System.currentTimeMillis());
        return data;
    }
}
