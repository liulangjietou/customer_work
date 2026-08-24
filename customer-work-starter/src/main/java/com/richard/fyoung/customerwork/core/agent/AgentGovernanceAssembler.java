package com.richard.fyoung.customerwork.core.agent;

import com.richard.fyoung.customerwork.core.middleware.HumanApprovalMiddleware;
import com.richard.fyoung.customerwork.core.middleware.AgentLifecycleMiddleware;
import com.richard.fyoung.customerwork.core.middleware.ObservabilityMiddleware;
import com.richard.fyoung.customerwork.core.memory.MemorySubjectResolver;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Agent 治理装配器（全链路唯一装配入口）。
 *
 * <p><b>为什么要有这个类</b>：本项目出现过多次同一形状的缺陷——某个治理能力
 * （token 计量、敏感词过滤、工具审批、脱敏、审计、租户上下文）只接在了当时被测试的那一条
 * 对话路径上，另一条路径照常裸奔。根因是「装配」这件事散落在多个 Agent 入口里各写一遍：
 * {@code CustomerServiceAgentFactory} 写一份、{@code MultiAgentOrchestrator} 写一份，
 * 新增能力时改了前者忘了后者，而两边都不会报错。</p>
 *
 * <p>本类把装配收敛成唯一实现，所有构建 {@link ReActAgent} 的入口一律调用
 * {@link #applyTo(ReActAgent.Builder)}，<b>新增治理中间件只需改这里一处</b>，
 * 所有路径自动获得。与 {@link TenantResolver} 收敛租户解析是同一个思路。</p>
 *
 * <p>装配顺序与此前 {@code CustomerServiceAgentFactory} 保持一致：
 * 可观测 → 人工确认（可选）→ 可插拔 Middleware（延迟/脱敏/审计/自我纠错/护栏/动态参数/租户
 * 及下游自定义的所有 {@link MiddlewareBase} Bean，按 {@code @Order} 排序）。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class AgentGovernanceAssembler {

    private final CustomerWorkProperties properties;
    private final TenantResolver tenantResolver;
    /** 可插拔 Middleware：本库内置 + 下游自定义的所有 {@link MiddlewareBase} Bean。 */
    private final ObjectProvider<MiddlewareBase> pluggableMiddlewares;
    /** 可为 null：未接入 Micrometer 时观测降级为仅日志。 */
    private final MeterRegistry meterRegistry;
    /** 运行时生命周期总闸门；所有 Agent 构建路径必须共享同一实例。 */
    private final RuntimeAgentAccessState runtimeAccessState;

    @Autowired
    public AgentGovernanceAssembler(CustomerWorkProperties properties,
                                    TenantResolver tenantResolver,
                                    ObjectProvider<MiddlewareBase> pluggableMiddlewares,
                                    ObjectProvider<MeterRegistry> meterRegistryProvider,
                                    RuntimeAgentAccessState runtimeAccessState) {
        this.properties = properties;
        this.tenantResolver = tenantResolver;
        this.pluggableMiddlewares = pluggableMiddlewares;
        this.meterRegistry = meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
        this.runtimeAccessState = runtimeAccessState;
    }

    /** 兼容离线单测与未装配热配置的叶子模块，默认保持启用态。 */
    public AgentGovernanceAssembler(CustomerWorkProperties properties,
                                    TenantResolver tenantResolver,
                                    ObjectProvider<MiddlewareBase> pluggableMiddlewares,
                                    ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(properties, tenantResolver, pluggableMiddlewares, meterRegistryProvider,
            RuntimeAgentAccessState.alwaysActive());
    }

    /**
     * 把全部治理中间件装配到给定的 Agent builder 上。
     *
     * <p>每一条对话路径构建 Agent 时都必须调用本方法；
     * {@code AgentAssemblyAlignmentTest} 会断言各入口都调到了，漏调即测试红。</p>
     *
     * @param builder 待装配的 Agent builder
     */
    public void applyTo(ReActAgent.Builder builder) {
        // 生命周期必须最先判定：撤销态不允许进入模型、MCP、Skill 或任何其它工具链。
        builder.middleware(new AgentLifecycleMiddleware(runtimeAccessState));

        // 可观测中间件（请求/工具/错误打点）
        builder.middleware(new ObservabilityMiddleware(meterRegistry));

        // Human-in-the-Loop：工具级人工确认（观测层，实际闸门由 Permission ask 规则承担）
        if (properties.getHumanApproval().isEnabled()) {
            builder.middleware(new HumanApprovalMiddleware(
                Set.copyOf(properties.getHumanApproval().getGuardedTools())));
        }

        // 可插拔 Middleware：内置（延迟/脱敏/审计/自我纠错/护栏/动态参数/租户/分段耗时与 token 计量）
        // + 下游自定义 MiddlewareBase Bean
        if (pluggableMiddlewares != null) {
            pluggableMiddlewares.orderedStream().forEach(builder::middleware);
        }
    }

    /**
     * 构造一次 Agent 调用的运行时上下文：把「会话 ID」映射为 2.0 的 {@code (userId, sessionId)}。
     *
     * <p>{@code userId} 取鉴权入口建立的可信租户上下文，整个会话 ID 仅作为
     * {@code sessionId}。没有请求上下文的存量内部调用才兼容旧会话前缀解析。</p>
     *
     * <p><b>这两个值决定了框架 {@code ReActAgent} 内部按 {@code slotKey(userId, sessionId)}
     * 缓存的对话状态落在哪个槽位上，因此绝不能写成常量</b>——写成常量会让所有用户共用一份对话历史。</p>
     *
     * @param sessionId 会话标识；其内容不是租户身份凭据
     */
    public RuntimeContext contextFor(String sessionId) {
        return contextFor(MemorySubjectResolver.CUSTOMER_SERVICE_AGENT, sessionId,
            AgentInvocationIdentity.CHANNEL_INTERNAL);
    }

    /** 冻结完整可信调用快照；渠道优先使用接入层已建立的值。 */
    public RuntimeContext contextFor(String agentCode, String sessionId, String fallbackChannel) {
        AgentInvocationIdentity identity = AgentInvocationIdentity.capture();
        String channel = identity != null && identity.channelCode() != null
            ? identity.channelCode() : fallbackChannel;
        AgentInvocationIdentity invocation = identity == null
            ? null : identity.forInvocation(channel, sessionId, agentCode);
        RuntimeContext.Builder b = RuntimeContext.builder()
            .userId(tenantResolver.resolve(sessionId))
            .sessionId(sessionId == null || sessionId.isBlank() ? "default" : sessionId);
        // org 维度：写入 KV 命名空间，实现 session/user/org 多维隔离
        String org = properties.getHarness().getOrg();
        if (org != null && !org.isBlank()) {
            b.put("org", org);
        }
        if (invocation != null) {
            b.put(AgentInvocationIdentity.class, invocation);
        }
        return b.build();
    }
}
