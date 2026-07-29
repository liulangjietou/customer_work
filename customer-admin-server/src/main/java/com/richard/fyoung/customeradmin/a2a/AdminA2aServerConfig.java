package com.richard.fyoung.customeradmin.a2a;

import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import io.a2a.spec.TransportProtocol;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;
import io.agentscope.core.a2a.server.transport.TransportProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * A2A 服务端装配：把一个后台智能体导出成标准 A2A Agent。
 *
 * <p>只在 {@code admin.a2a.enabled=true} 且配了 {@code admin.a2a.agent-code} 时生效，理由见
 * {@link AdminA2aProperties} 的类注释。</p>
 *
 * <p><b>框架的 A2A Server 不监听端口、不注册路由</b>（其类注释明说 "The Server is not listen ports and
 * export endpoints"），只负责把协议处理链装配好。端点由 {@link A2aController} 用本模块现成的
 * Spring MVC 暴露——这反而省事：鉴权、日志、错误处理都自动沿用本模块既有的那一套，
 * 不需要为 A2A 单起一个内嵌 HTTP 服务。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(prefix = "admin.a2a", name = "enabled", havingValue = "true")
public class AdminA2aServerConfig {

    private static final Logger log = LoggerFactory.getLogger(AdminA2aServerConfig.class);

    /** JSON-RPC 端点路径，与 {@link A2aController} 的映射保持一致。 */
    static final String JSONRPC_PATH = "/a2a/jsonrpc";

    private static final String DEFAULT_DESCRIPTION = "AgentScope-based agent exported over A2A protocol";
    /** A2A 的输入/输出内容类型：当前导出的智能体只处理纯文本。 */
    private static final List<String> TEXT_MODES = List.of("text/plain");

    @Bean
    public AgentScopeA2aServer agentScopeA2aServer(AdminA2aProperties properties,
                                                   AgentInstanceCache agentInstanceCache,
                                                   AiAgentMapper agentMapper) {
        String agentCode = properties.getAgentCode();
        if (!StringUtils.hasText(agentCode)) {
            throw new IllegalStateException("admin.a2a.enabled=true requires admin.a2a.agent-code");
        }
        AiAgent agent = agentMapper.selectOne(new LambdaQueryWrapper<AiAgent>()
            .eq(AiAgent::getAgentCode, agentCode).last("LIMIT 1"));
        if (agent == null) {
            // fast fail：配了一个不存在的智能体却让服务照常起来，只会在第一个外部请求进来时才暴露
            throw new IllegalStateException("admin.a2a.agent-code not found: " + agentCode);
        }
        String description = StringUtils.hasText(properties.getDescription())
            ? properties.getDescription() : DEFAULT_DESCRIPTION;

        ConfigurableAgentCard card = new ConfigurableAgentCard.Builder()
            .name(agent.getAgentName())
            .description(description)
            .url(properties.getBaseUrl() + JSONRPC_PATH)
            .version(properties.getVersion())
            .defaultInputModes(TEXT_MODES)
            .defaultOutputModes(TEXT_MODES)
            .preferredTransport(TransportProtocol.JSONRPC.asString())
            .build();

        AgentScopeA2aServer server = AgentScopeA2aServer
            .builder(new AdminAgentRunner(agentInstanceCache, agentCode, description))
            .agentCard(card)
            .withTransport(TransportProperties.builder(TransportProtocol.JSONRPC.asString())
                .path(JSONRPC_PATH)
                .build())
            .build();

        log.info("[a2a] server assembled: agentCode={} url={}{}", agentCode, properties.getBaseUrl(), JSONRPC_PATH);
        return server;
    }
}
