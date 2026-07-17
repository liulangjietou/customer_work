package com.richard.fyoung.customerworkgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * customer-work-gateway 启动类（Spring Cloud Gateway 统一路由，端口 8888）。
 *
 * <p>经 Nacos 服务发现（namespace={@code customer-work}，group={@code DEFAULT_GROUP}，须与服务端注册对齐）
 * 把外部流量按路径前缀反向代理到两个后端：{@code /admin/**} → admin-server(8082)、
 * {@code /app/**} → app-server(8080)、{@code /ws/**} → app-server 的 WebSocket。路由与发现配置见
 * {@code application.yml}。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@SpringBootApplication
public class CustomerWorkGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerWorkGatewayApplication.class, args);
    }
}
