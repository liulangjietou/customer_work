package com.richard.fyoung.customerworkapp.service;

import com.richard.fyoung.customerwork.data.ticket.Ticket;
import com.richard.fyoung.customerwork.data.ticket.TicketService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 用户会话资源的统一归属守卫。
 *
 * <p>附件、反馈、满意度、消息回放都是会话的下级资源，归属必须回溯到工单根资源，不能信任
 * 可伪造的 sessionId 前缀。不存在和不属于当前用户统一返回 404，避免通过状态码枚举他人会话。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class UserSessionGuard {

    private final TicketService ticketService;

    public UserSessionGuard(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    /** 返回已确认归属的工单；未知或他人会话均 fail-closed。 */
    public Ticket requireOwned(String sessionId, String userId) {
        Ticket ticket = ticketService.findBySession(sessionId)
            .orElseThrow(UserSessionGuard::notFound);
        if (!userId.equals(ticket.getUserId())) {
            throw notFound();
        }
        return ticket;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
    }
}
