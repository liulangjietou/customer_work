package com.example.customerwork.tool;

import com.example.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.higress.HigressMcpClientBuilder;
import io.agentscope.extensions.higress.HigressMcpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * Higress AI 网关接入装配器（对应「接入 Higress」）。
 *
 * <p>Higress 作为统一 AI 网关，承担 Agent 与后端工具/LLM 之间的流量治理、路由与按需工具发现。
 * 本装配器按配置把 Higress 的 MCP 端点接入 {@link Toolkit}：开启 {@code tool-search} 后，
 * Higress 会按关键词动态返回相关工具，避免一次性把上百个工具 Schema 塞进上下文。</p>
 *
 * <p>默认关闭；配置 {@code customer-work.higress.enabled=true} 且给定 endpoint 后生效。
 * 注册在装配期同步完成（一次性初始化）。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class HigressToolkitConfigurer {

    private static final Logger log = LoggerFactory.getLogger(HigressToolkitConfigurer.class);

    private final CustomerWorkProperties properties;

    public HigressToolkitConfigurer(CustomerWorkProperties properties) {
        this.properties = properties;
    }

    /** 是否启用 Higress（开关开启且配置了 endpoint）。 */
    public boolean isEnabled() {
        CustomerWorkProperties.Higress h = properties.getHigress();
        return h.isEnabled() && StringUtils.hasText(h.getEndpoint());
    }

    /** 把 Higress MCP 客户端注册到 toolkit。未启用时为 no-op。 */
    public void configure(Toolkit toolkit) {
        if (!isEnabled()) {
            return;
        }
        CustomerWorkProperties.Higress h = properties.getHigress();
        try {
            HigressMcpClientWrapper wrapper = buildClient(h).block();
            if (wrapper != null) {
                toolkit.registerMcpClient(wrapper).block();
                log.info("[Higress] 已接入 AI 网关 endpoint={} toolSearch={}",
                    h.getEndpoint(), h.getToolSearch());
            }
        } catch (Exception e) {
            // Higress 不可用不应阻断应用启动
            log.error("[Higress] 接入失败 endpoint={}: {}", h.getEndpoint(), e.getMessage());
        }
    }

    private reactor.core.publisher.Mono<HigressMcpClientWrapper> buildClient(
            CustomerWorkProperties.Higress h) {
        HigressMcpClientBuilder builder = HigressMcpClientBuilder.create(h.getName())
            .timeout(Duration.ofSeconds(h.getTimeoutSeconds()));
        if ("streamable-http".equalsIgnoreCase(h.getTransport())) {
            builder.streamableHttpEndpoint(h.getEndpoint());
        } else {
            builder.sseEndpoint(h.getEndpoint());
        }
        if (StringUtils.hasText(h.getToolSearch())) {
            builder.toolSearch(h.getToolSearch(), h.getMaxTools());
        }
        return builder.buildAsync();
    }
}
