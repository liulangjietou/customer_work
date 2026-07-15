package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerworkapp.dao.UserOrderDao;
import com.richard.fyoung.customerworkapp.dao.UserOrderDao.OrderView;
import com.richard.fyoung.customerworkapp.dao.UserOrderDao.OwnedOrder;
import com.richard.fyoung.customerworkapp.security.UserAuthWebFilter;
import com.richard.fyoung.customerworkapp.security.UserJwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;

/**
 * 用户订单端点切片测试：列表、详情、越权 403、不存在 404、数据源未启用 503。
 * @author owlzhangfq@gmail.com
 */
@WebFluxTest(UserOrderController.class)
@Import({CustomerWorkProperties.class, UserJwtService.class, UserAuthWebFilter.class})
class UserOrderControllerTest {

    private static final String USER_ID = "U1";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserJwtService jwtService;

    @MockBean
    private UserOrderDao orderDao;

    private String bearer() {
        return "Bearer " + jwtService.issue(USER_ID, "alice", "Alice");
    }

    private OrderView sampleView() {
        return new OrderView("2026071500001", "P001", "旗舰款无线降噪耳机", "299.00",
            "已发货", "北京市海淀区中关村大街 1 号", "[已揽收]→[派送中]", 1_700_000_000_000L);
    }

    @Test
    void list_shouldReturnUserOrders() {
        when(orderDao.isEnabled()).thenReturn(true);
        when(orderDao.listByUser(USER_ID)).thenReturn(List.of(sampleView()));

        webTestClient.get().uri("/api/customer/user/orders")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$[0].orderId").isEqualTo("2026071500001")
            .jsonPath("$[0].amount").isEqualTo("299.00")
            .jsonPath("$[0].status").isEqualTo("已发货");
    }

    @Test
    void detail_owned_shouldReturnWithLogistics() {
        when(orderDao.isEnabled()).thenReturn(true);
        when(orderDao.findById("2026071500001")).thenReturn(Optional.of(new OwnedOrder(USER_ID, sampleView())));

        webTestClient.get().uri("/api/customer/user/orders/2026071500001")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.orderId").isEqualTo("2026071500001")
            .jsonPath("$.logisticsTrace").isEqualTo("[已揽收]→[派送中]");
    }

    @Test
    void detail_notOwner_shouldReturn403() {
        when(orderDao.isEnabled()).thenReturn(true);
        when(orderDao.findById("2026071500001")).thenReturn(Optional.of(new OwnedOrder("OTHER", sampleView())));

        webTestClient.get().uri("/api/customer/user/orders/2026071500001")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .exchange()
            .expectStatus().isForbidden();
    }

    @Test
    void detail_notFound_shouldReturn404() {
        when(orderDao.isEnabled()).thenReturn(true);
        when(orderDao.findById("nope")).thenReturn(Optional.empty());

        webTestClient.get().uri("/api/customer/user/orders/nope")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void list_datasourceDisabled_shouldReturn503() {
        when(orderDao.isEnabled()).thenReturn(false);

        webTestClient.get().uri("/api/customer/user/orders")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .exchange()
            .expectStatus().isEqualTo(503);
    }

    @Test
    void list_withoutToken_shouldReturn401() {
        webTestClient.get().uri("/api/customer/user/orders")
            .exchange()
            .expectStatus().isUnauthorized();
    }
}
