package com.richard.fyoung.customerweb;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.config.ModelConfig;
import com.richard.fyoung.customerwork.config.PermissionConfig;
import com.richard.fyoung.customerwork.config.SessionConfig;
import com.richard.fyoung.customerwork.middleware.ObservabilityMiddleware;
import com.richard.fyoung.customerwork.tool.ToolRegistrar;
import com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.MockKnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.MockOrderBackend;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * customer-web 的 Agent 集成配置：复用 customer-work 的客服 Agent 能力，把 Agent / Model / Toolkit /
 * AgentStateStore 暴露为 Spring Bean，由 {@code agentscope-admin} 自动接管（agents/tools/permissions/usage 端点）。
 *
 * <p>本模块以 {@code spring.autoconfigure.exclude} 关闭了 starter 的 WebFlux 自动装配，仅复用其 web 无关的
 * 叶子类（{@link ModelConfig} / {@link SessionConfig} / {@link ToolRegistrar} / {@link PermissionConfig} /
 * {@link ObservabilityMiddleware}），从而与 admin 的 Spring MVC 栈零冲突。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableConfigurationProperties(CustomerWorkProperties.class)
public class CustomerWebAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(CustomerWebAgentConfig.class);

    private static final String SYSTEM_PROMPT = """
        你是一名专业、耐心的电商智能客服助手。先理解用户意图（咨询 / 订单查询 / 售后退款 / 投诉），
        咨询类优先依据知识库回答并保留来源；订单/物流类调用订单工具查询后回答；退款类必须先校验退款资格，
        涉及资金只生成待人工确认工单、绝不承诺已打款；情绪强烈或大额高风险时调用人工转接工具升级。
        回答简洁、准确、有礼貌，不得编造事实。
        """;

    /** 模型层（复用 ModelConfig 的多厂商 + 兜底/重试装配）。 */
    @Bean
    public Model chatModel(CustomerWorkProperties properties) {
        return new ModelConfig().chatModel(properties);
    }

    /** 状态外置存储（复用 SessionConfig：memory/json/redis/mysql AgentStateStore）。 */
    @Bean
    public AgentStateStore agentStateStore(CustomerWorkProperties properties) {
        return new SessionConfig().agentStateStore(properties);
    }

    /** 权限上下文（复用 PermissionConfig：三态工具授权）。 */
    @Bean
    public PermissionContextState permissionContextState(CustomerWorkProperties properties) {
        return new PermissionConfig().permissionContextState(properties);
    }

    /** 业务工具集（复用 ToolRegistrar + 默认 Mock 后端；生产可换真实后端）。 */
    @Bean
    public Toolkit customerToolkit() {
        Toolkit toolkit = new Toolkit();
        new ToolRegistrar(new MockOrderBackend(), new MockAfterSalesBackend(), new MockKnowledgeBackend())
            .registerBusinessTools(toolkit);
        return toolkit;
    }

    /**
     * 客服 Agent（暴露为 {@link Agent} Bean → 被 agentscope-admin 的 AgentRegistry 自动接管）。
     *
     * <p>与 customer-work 主链路同栈：系统提示词 + 业务工具 + 状态存储 + 三态权限 + 可观测中间件。</p>
     */
    @Bean
    public Agent customerServiceAgent(Model chatModel,
                                      Toolkit customerToolkit,
                                      AgentStateStore agentStateStore,
                                      PermissionContextState permissionContextState,
                                      CustomerWorkProperties properties) {
        ReActAgent agent = ReActAgent.builder()
            .name("CustomerServiceAgent")
            .sysPrompt(SYSTEM_PROMPT)
            .model(chatModel)
            .toolkit(customerToolkit)
            .stateStore(agentStateStore)
            .defaultSessionId("console")
            .permissionContext(permissionContextState)
            .middleware(new ObservabilityMiddleware())
            .maxIters(properties.getAgent().getMaxIters())
            .enablePendingToolRecovery(properties.getInterrupt().isPendingToolRecoveryEnabled())
            .build();
        log.info("[customer-web] customer-service agent registered to admin console: name={}",
            agent.getName());
        return agent;
    }
}
