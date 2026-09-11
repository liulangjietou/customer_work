package com.richard.fyoung.customerwork.data.rag.search;

import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import io.agentscope.core.agent.RuntimeContext;

/**
 * 知识盲区埋点接口（检索侧 SPI）。
 *
 * <p>检索链路只负责"发现未命中"并往这里记一笔，不关心盲区怎么聚合、落哪张表、按什么维度分区——
 * 那是 {@code capability/knowledgegap} 的职责。声明成接口是为了让依赖方向反过来：
 * 由能力侧实现检索侧的接口，检索包不反向依赖能力包。</p>
 *
 * <p>RAG 有两条互不相通的路径，<b>两条都必须埋点</b>：</p>
 * <ul>
 *   <li>工具路径 —— 模型主动调 {@code KnowledgeBaseTools#searchKnowledge}，
 *       未命中判定走 {@code KnowledgeBackend.isMiss} 的文案契约；</li>
 *   <li>中间件路径 —— {@link KnowledgeInjectionMiddleware} 自动注入召回块（后台工作台智能体走这条），
 *       未命中判定是"检索正常返回但召回块为空"。</li>
 * </ul>
 *
 * <p>此前只有工具路径埋了点，中间件路径的未命中一条都统计不到，盲区看板因此只反映半条链路。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@FunctionalInterface
public interface KnowledgeGapRecorder {

    /**
     * 记录一次知识未命中。
     *
     * @param sessionId 会话标识；传 {@code null} 走默认分区（盲区排行是全局运营视角，不按会话细分）
     * @param question  用户问题原文
     */
    void recordMiss(String sessionId, String question);

    /** 兼容已有实现；支持来源的记录器覆盖此方法。 */
    default void recordMiss(String sessionId, String question, KnowledgeGapEvidence evidence) {
        recordMiss(sessionId, question);
    }

    /** 在调度前冻结租户和来源；返回的旁路动作可在异步检索完成后执行。 */
    default Runnable captureMiss(String question, RuntimeContext context,
                                  KnowledgeGapEvidence.Path path, String executingAgent) {
        AgentInvocationIdentity identity = context == null ? null : context.get(AgentInvocationIdentity.class);
        String tenant = identity == null ? TenantContext.get() : identity.tenantId();
        String session = context == null ? null : context.getSessionId();
        KnowledgeGapEvidence evidence = KnowledgeGapEvidence.capture(context, path, executingAgent);
        return () -> TenantContext.runWith(tenant, () -> recordMiss(session, question, evidence));
    }
}
