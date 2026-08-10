package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.core.common.PageResult;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.data.order.OrderDirectoryRow;
import com.richard.fyoung.customerwork.data.order.OrderDirectoryService;
import com.richard.fyoung.customerwork.data.order.OrderMutationResult;
import com.richard.fyoung.customerwork.safety.security.AgentAccessCredential;
import com.richard.fyoung.customerwork.safety.security.AgentAuthWebFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 坐席订单端点切片测试：分页 {total,items} 契约、详情 404、取消 409、鉴权 401。
 * Service 被 mock，覆盖 HTTP 行为与领域结果到状态码的映射。
 * @author owlzhangfq@gmail.com
 */
@WebFluxTest(AgentOrderController.class)
@Import({AgentAuthWebFilter.class, AgentOrderControllerTest.Cfg.class})
class AgentOrderControllerTest {

    private static final String SECRET = "agent-order-secret";
    private static final String AGENT_ID = "agent-3";

    @TestConfiguration
    static class Cfg {
        @Bean
        CustomerWorkProperties customerWorkProperties() {
            CustomerWorkProperties props = new CustomerWorkProperties();
            props.getAgentAccess().setSecret(SECRET);
            return props;
        }
    }

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private OrderDirectoryService orderDirectoryService;

    private String token() {
        return AgentAccessCredential.sign(AGENT_ID, System.currentTimeMillis() + 60_000, SECRET);
    }

    private static OrderDirectoryRow row(String orderId, String status) {
        OrderDirectoryRow r = new OrderDirectoryRow();
        r.setOrderId(orderId);
        r.setUserId("U1");
        r.setUsername("alice");
        r.setProductId("P001");
        r.setProductName("耳机");
        r.setAmount(new BigDecimal("299.00"));
        r.setStatus(status);
        r.setReceiverAddr("北京市朝阳区");
        r.setLogisticsTrace("[已揽收]");
        r.setCreatedAtMs(1781049600000L);
        return r;
    }

    @Test
    void page_shouldReturnTotalAndItems() {
        when(orderDirectoryService.isEnabled()).thenReturn(true);
        when(orderDirectoryService.page(any()))
            .thenReturn(new PageResult<>(1, List.of(row("O-1", "待发货"))));

        webTestClient.get().uri("/api/customer/agent/orders?username=alice&page=1&size=20")
            .header("X-Agent-Token", token())
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.total").isEqualTo(1)
            .jsonPath("$.items[0].orderId").isEqualTo("O-1")
            .jsonPath("$.items[0].username").isEqualTo("alice")
            .jsonPath("$.items[0].amount").isEqualTo("299.00");
    }

    @Test
    void detail_notFound_shouldReturn404() {
        when(orderDirectoryService.isEnabled()).thenReturn(true);
        when(orderDirectoryService.findDetail("O-404")).thenReturn(Optional.empty());

        webTestClient.get().uri("/api/customer/agent/orders/O-404")
            .header("X-Agent-Token", token())
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void detail_found_shouldReturnLogistics() {
        when(orderDirectoryService.isEnabled()).thenReturn(true);
        when(orderDirectoryService.findDetail("O-1")).thenReturn(Optional.of(row("O-1", "已发货")));

        webTestClient.get().uri("/api/customer/agent/orders/O-1")
            .header("X-Agent-Token", token())
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.orderId").isEqualTo("O-1")
            .jsonPath("$.logisticsTrace").isEqualTo("[已揽收]");
    }

    @Test
    void modifyAddress_blank_shouldReturn400() {
        webTestClient.post().uri("/api/customer/agent/orders/O-1/modify-address")
            .header("X-Agent-Token", token())
            .bodyValue(java.util.Map.of("newAddress", ""))
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void modifyAddress_ok_shouldReturn200() {
        when(orderDirectoryService.isEnabled()).thenReturn(true);
        when(orderDirectoryService.modifyAddress("O-1", "新地址")).thenReturn(OrderMutationResult.OK);

        webTestClient.post().uri("/api/customer/agent/orders/O-1/modify-address")
            .header("X-Agent-Token", token())
            .bodyValue(java.util.Map.of("newAddress", "新地址"))
            .exchange()
            .expectStatus().isOk();
    }

    @Test
    void cancel_stateConflict_shouldReturn409() {
        when(orderDirectoryService.isEnabled()).thenReturn(true);
        when(orderDirectoryService.cancel("O-1", "太晚"))
            .thenReturn(OrderMutationResult.STATE_CONFLICT);

        webTestClient.post().uri("/api/customer/agent/orders/O-1/cancel")
            .header("X-Agent-Token", token())
            .bodyValue(java.util.Map.of("reason", "太晚"))
            .exchange()
            .expectStatus().isEqualTo(409);
    }

    @Test
    void cancel_notFound_shouldReturn404() {
        when(orderDirectoryService.isEnabled()).thenReturn(true);
        when(orderDirectoryService.cancel("O-404", "x")).thenReturn(OrderMutationResult.NOT_FOUND);

        webTestClient.post().uri("/api/customer/agent/orders/O-404/cancel")
            .header("X-Agent-Token", token())
            .bodyValue(java.util.Map.of("reason", "x"))
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void missingToken_shouldReturn401() {
        webTestClient.get().uri("/api/customer/agent/orders/O-1")
            .exchange()
            .expectStatus().isUnauthorized();
    }
}
