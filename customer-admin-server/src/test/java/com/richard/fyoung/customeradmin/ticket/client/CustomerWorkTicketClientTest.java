package com.richard.fyoung.customeradmin.ticket.client;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.ticket.dto.TicketMessageVO;
import com.richard.fyoung.customeradmin.ticket.dto.TicketPageQuery;
import com.richard.fyoung.customeradmin.ticket.dto.TicketPageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link CustomerWorkTicketClient} 单测：用 {@link MockRestServiceServer} mock 8080 坐席 API，
 * 覆盖 200 正常解析、409→{@link ResultCode#TICKET_STATE_CONFLICT}、连接异常→
 * {@link ResultCode#CUSTOMER_WORK_UNAVAILABLE}。
 * @author owlzhangfq@gmail.com
 */
class CustomerWorkTicketClientTest {

    private static final String BASE_URL = "http://localhost:8080";

    private MockRestServiceServer server;
    private CustomerWorkTicketClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CustomerWorkTicketClient(builder.build());
    }

    @Test
    void page_shouldParse200Body() {
        server.expect(requestTo(containsString("/api/customer/agent/tickets?")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(
                "{\"total\":2,\"items\":[{\"id\":\"TK-1001\",\"status\":\"OPEN\",\"assignee\":\"alice\"}]}",
                MediaType.APPLICATION_JSON));

        TicketPageQuery query = new TicketPageQuery();
        query.setStatus("OPEN");
        TicketPageResult result = client.page(query);

        assertEquals(2, result.getTotal());
        assertEquals(1, result.getItems().size());
        assertEquals("TK-1001", result.getItems().get(0).getId());
        assertEquals("OPEN", result.getItems().get(0).getStatus());
        assertEquals("alice", result.getItems().get(0).getAssignee());
        server.verify();
    }

    @Test
    void messages_shouldParse200JsonArray() {
        server.expect(requestTo(containsString("/api/customer/agent/tickets/TK-1007/messages")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(
                "[{\"id\":10,\"ticketId\":\"TK-1007\",\"senderType\":\"USER\",\"content\":\"hi\"}]",
                MediaType.APPLICATION_JSON));

        List<TicketMessageVO> messages = client.messages("TK-1007", null, 20);

        assertEquals(1, messages.size());
        assertEquals(10L, messages.get(0).getId());
        assertEquals("TK-1007", messages.get(0).getTicketId());
        assertEquals("USER", messages.get(0).getSenderType());
        server.verify();
    }

    @Test
    void claim_shouldMapConflictToBizException() {
        server.expect(requestTo(containsString("/api/customer/agent/tickets/TK-1005/claim")))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.CONFLICT));

        BizException ex = assertThrows(BizException.class, () -> client.claim("TK-1005"));
        assertEquals(ResultCode.TICKET_STATE_CONFLICT, ex.getResultCode());
        server.verify();
    }

    @Test
    void detail_shouldMapConnectionFailureToBizException() {
        server.expect(requestTo(containsString("/api/customer/agent/tickets/TK-1009")))
            .andRespond(request -> {
                throw new IOException("connection refused");
            });

        BizException ex = assertThrows(BizException.class, () -> client.detail("TK-1009"));
        assertEquals(ResultCode.CUSTOMER_WORK_UNAVAILABLE, ex.getResultCode());
        server.verify();
    }
}
