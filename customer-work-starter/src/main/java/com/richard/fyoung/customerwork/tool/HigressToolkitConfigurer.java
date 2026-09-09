package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.core.constant.McpTimeouts;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.tool.mcp.McpServerSpec;
import com.richard.fyoung.customerwork.tool.mcp.McpSecurityPolicy;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.higress.HigressMcpClientBuilder;
import io.agentscope.extensions.higress.HigressMcpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import com.richard.fyoung.customerwork.infra.config.properties.HigressProperties;

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
    private final McpSecurityPolicy securityPolicy;

    public HigressToolkitConfigurer(CustomerWorkProperties properties) {
        this.properties = properties;
        this.securityPolicy = new McpSecurityPolicy(properties.getMcp()::getAllowedHosts,
            java.util.List::of, java.util.List::of, java.util.List::of);
    }

    /** 是否启用 Higress（开关开启且配置了 endpoint）。 */
    public boolean isEnabled() {
        HigressProperties h = properties.getHigress();
        return h.isEnabled() && StringUtils.hasText(h.getEndpoint());
    }

    /** 把 Higress MCP 客户端注册到 toolkit。未启用时为 no-op。 */
    public void configure(Toolkit toolkit) {
        if (!isEnabled()) {
            return;
        }
        HigressProperties h = properties.getHigress();
        HigressMcpClientWrapper wrapper = null;
        try {
            wrapper = buildClient(h).block(McpTimeouts.BUILD);
            if (wrapper != null) {
                toolkit.registerMcpClient(wrapper).block(McpTimeouts.REGISTER);
                log.info("[Higress] AI gateway registered, endpoint={} toolSearch={}",
                    h.getEndpoint(), h.getToolSearch());
                wrapper = null; // 生命周期已交给 ManagedToolkit。
            }
        } catch (Exception e) {
            closeRegistrationFailure(wrapper, h.getName());
            // Higress 不可用不应阻断应用启动
            log.error("[Higress] registration failed, code={} endpoint={}",
                "HIGRESS-REGISTER-FAIL", h.getEndpoint(), e);
        }
    }

    private void closeRegistrationFailure(HigressMcpClientWrapper wrapper, String name) {
        if (wrapper == null) {
            return;
        }
        try {
            wrapper.close();
        } catch (Exception closeError) {
            log.error("[Higress] registration rollback close failed, code={} name={}",
                "HIGRESS-REGISTER-ROLLBACK-CLOSE-FAIL", name, closeError);
        }
    }

    private reactor.core.publisher.Mono<HigressMcpClientWrapper> buildClient(
            HigressProperties h) {
        String safeEndpoint = securityPolicy.validateRemoteUrl(h.getEndpoint());
        HigressMcpClientBuilder builder = HigressMcpClientBuilder.create(h.getName())
            .timeout(Duration.ofSeconds(h.getTimeoutSeconds()));
        if (McpServerSpec.TRANSPORT_STREAMABLE_HTTP.equalsIgnoreCase(h.getTransport())) {
            builder.streamableHttpEndpoint(safeEndpoint);
        } else {
            builder.sseEndpoint(safeEndpoint);
        }
        if (StringUtils.hasText(h.getToolSearch())) {
            builder.toolSearch(h.getToolSearch(), h.getMaxTools());
        }
        return builder.buildAsync();
    }
}
