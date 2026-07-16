package com.richard.fyoung.customerworkapp.ws;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 响应式 WebSocket 装配（spring-webflux 原生，无额外依赖）。
 *
 * <p>用 {@link SimpleUrlHandlerMapping} 把 {@code /ws/user}、{@code /ws/agent} 两个路径绑定到对应
 * {@link WebSocketHandler}，order 置于高优先级（{@code HIGHEST_PRECEDENCE}）确保先于注解型 RequestMapping
 * 命中；{@link WebSocketHandlerAdapter} 负责把 WebSocket 握手升级适配进 WebFlux 派发链。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class WebSocketConfig {

    private static final String PATH_USER = "/ws/user";
    private static final String PATH_AGENT = "/ws/agent";

    @Bean
    public HandlerMapping webSocketHandlerMapping(UserChatWebSocketHandler userHandler,
                                                  AgentChatWebSocketHandler agentHandler) {
        Map<String, WebSocketHandler> map = new LinkedHashMap<>();
        map.put(PATH_USER, userHandler);
        map.put(PATH_AGENT, agentHandler);

        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(map);
        // 高于注解 RequestMapping（默认 order=0），确保 /ws/* 走 WebSocket 升级而非普通请求
        mapping.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
