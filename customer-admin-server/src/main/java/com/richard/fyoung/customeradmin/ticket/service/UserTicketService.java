package com.richard.fyoung.customeradmin.ticket.service;

import com.richard.fyoung.customeradmin.ticket.client.CustomerWorkTicketClient;
import com.richard.fyoung.customeradmin.ticket.config.CustomerWorkClientProperties;
import com.richard.fyoung.customeradmin.ticket.dto.TicketDetailVO;
import com.richard.fyoung.customeradmin.ticket.dto.TicketMessageVO;
import com.richard.fyoung.customeradmin.ticket.dto.TicketPageQuery;
import com.richard.fyoung.customeradmin.ticket.dto.TicketPageResult;
import com.richard.fyoung.customeradmin.ticket.dto.WsCredentialVO;
import com.richard.fyoung.customerwork.security.AgentAccessCredential;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 用户工单服务：坐席操作全部薄中转到 {@link CustomerWorkTicketClient}（工单数据在 8080 侧，
 * 本模块不建业务表）；WS 接入凭证在本地用 {@link AgentAccessCredential} 现签，不走 8080。
 * @author owlzhangfq@gmail.com
 */
@Service
public class UserTicketService {

    private final CustomerWorkTicketClient client;
    private final CustomerWorkClientProperties properties;

    public UserTicketService(CustomerWorkTicketClient client, CustomerWorkClientProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public TicketPageResult page(TicketPageQuery query) {
        return client.page(query);
    }

    public TicketDetailVO detail(String id) {
        return client.detail(id);
    }

    public List<TicketMessageVO> messages(String id, Long beforeId, Integer limit) {
        return client.messages(id, beforeId, limit);
    }

    public void claim(String id) {
        client.claim(id);
    }

    public void reply(String id, String content) {
        client.reply(id, content);
    }

    public void hold(String id, String reason) {
        client.hold(id, reason);
    }

    public void resume(String id) {
        client.resume(id);
    }

    public void transfer(String id, String toAgent) {
        client.transfer(id, toAgent);
    }

    public void resolve(String id, String note) {
        client.resolve(id, note);
    }

    public void close(String id, String reason) {
        client.close(id, reason);
    }

    public void updatePriority(String id, String priority) {
        client.updatePriority(id, priority);
    }

    public void updateCategory(String id, String category) {
        client.updateCategory(id, category);
    }

    /**
     * 签发坐席 WS 接入凭证：客服浏览器凭此直连 8080 的 {@code /ws/agent}。
     * 令牌用与 8080 共享的密钥现签，有效期 {@code credentialExpireHours} 小时。
     *
     * @param agentId 当前登录坐席登录名（由 Controller 从 Sa-Token 解析后传入）
     */
    public WsCredentialVO issueWsCredential(String agentId) {
        long expiresAtMs = System.currentTimeMillis()
            + Duration.ofHours(properties.getCredentialExpireHours()).toMillis();
        String token = AgentAccessCredential.sign(agentId, expiresAtMs, properties.getAgentSecret());
        return new WsCredentialVO(token, properties.getWsUrl(), expiresAtMs, agentId);
    }
}
