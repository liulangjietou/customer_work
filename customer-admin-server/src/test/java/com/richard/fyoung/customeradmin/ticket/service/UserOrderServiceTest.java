package com.richard.fyoung.customeradmin.ticket.service;

import com.richard.fyoung.customeradmin.ticket.client.CustomerWorkTicketClient;
import com.richard.fyoung.customeradmin.ticket.dto.OrderPageQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link UserOrderService} 单测：mock client 验证订单查询/改址/取消参数原样透传。
 * @author owlzhangfq@gmail.com
 */
class UserOrderServiceTest {

    private CustomerWorkTicketClient client;
    private UserOrderService service;

    @BeforeEach
    void setUp() {
        client = mock(CustomerWorkTicketClient.class);
        service = new UserOrderService(client);
    }

    @Test
    void orderOperations_shouldPassThroughToClient() {
        OrderPageQuery query = new OrderPageQuery();
        service.page(query);
        verify(client).pageOrders(query);

        service.detail("O-1");
        verify(client).orderDetail("O-1");

        service.modifyAddress("O-1", "新地址");
        verify(client).modifyOrderAddress("O-1", "新地址");

        service.cancel("O-1", "用户申请");
        verify(client).cancelOrder("O-1", "用户申请");
    }
}
