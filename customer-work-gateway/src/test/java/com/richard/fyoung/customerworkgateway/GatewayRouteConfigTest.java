package com.richard.fyoung.customerworkgateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关路由配置加载测试：上下文能起、RouteLocator 存在且三条路由（admin/app/ws）正确解析。
 *
 * <p>关闭 Nacos 服务发现（{@code spring.cloud.discovery.enabled=false}）以便<b>离线</b>验证路由配置本身——
 * 路由 URI 为 {@code lb://}，仅在请求到达时才解析实例，故收集路由 id 不需要真实 Nacos。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false"
    })
class GatewayRouteConfigTest {

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void routesLoaded() {
        assertNotNull(routeLocator, "RouteLocator 应装配");
        List<String> routeIds = routeLocator.getRoutes().map(Route::getId).collectList().block();
        assertNotNull(routeIds, "应能收集到路由");
        assertTrue(routeIds.contains("customer-admin-server"), "应含 admin 路由");
        assertTrue(routeIds.contains("customer-work-app-server"), "应含 app-server 路由");
        assertTrue(routeIds.contains("customer-work-app-server-ws"), "应含 WebSocket 路由");
    }
}
