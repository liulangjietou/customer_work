package com.richard.fyoung.customeradmin.ticket.service;

import com.richard.fyoung.customeradmin.ticket.client.CustomerWorkTicketClient;
import com.richard.fyoung.customeradmin.ticket.dto.OrderDetailVO;
import com.richard.fyoung.customeradmin.ticket.dto.OrderPageQuery;
import com.richard.fyoung.customeradmin.ticket.dto.OrderPageResult;
import org.springframework.stereotype.Service;

/**
 * 用户订单服务：坐席对订单的查询/改址/取消全部薄中转到 {@link CustomerWorkTicketClient}
 * （订单数据在 8080 侧，本模块不建业务表）。
 * @author owlzhangfq@gmail.com
 */
@Service
public class UserOrderService {

    private final CustomerWorkTicketClient client;

    public UserOrderService(CustomerWorkTicketClient client) {
        this.client = client;
    }

    public OrderPageResult page(OrderPageQuery query) {
        return client.pageOrders(query);
    }

    public OrderDetailVO detail(String orderId) {
        return client.orderDetail(orderId);
    }

    public void modifyAddress(String orderId, String newAddress) {
        client.modifyOrderAddress(orderId, newAddress);
    }

    public void cancel(String orderId, String reason) {
        client.cancelOrder(orderId, reason);
    }
}
