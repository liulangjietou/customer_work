package com.richard.fyoung.customerwork.data.rag.search;

import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;

/**
 * 知识库召回的<b>来源</b>抽象：给定智能体与本轮提问，返回一段可直接注入模型上下文的文本块。
 *
 * <p>starter 只定义"从哪里拿召回文本"这一件事，具体怎么拿由宿主实现——admin 侧是
 * "查智能体绑定的知识库 → 调 {@link KnowledgeSearchOps#searchAll} → 渲染成带编号与来源的块"，
 * 其它宿主完全可以换成本地向量库、静态文档甚至固定话术。注入时机与形态由
 * {@link KnowledgeInjectionMiddleware} 统一负责，实现方不必关心。</p>
 *
 * <p><b>实现约定</b>（中间件依赖这两条，实现方必须遵守）：
 * <ul>
 *   <li>无需注入时返回 {@code null} 或空串（未绑知识库 / 未命中 / 失败降级），
 *       绝不返回空的包裹标签，否则会平白污染上下文；</li>
 *   <li>本方法<b>允许阻塞</b>（一般是同步 HTTP），中间件已用
 *       {@code Schedulers.boundedElastic()} 承接，实现方无需自己异步化。</li>
 * </ul></p>
 * @author owlzhangfq@gmail.com
 */
@FunctionalInterface
public interface KnowledgeRetrievalProvider {

    /**
     * 按提问检索并渲染成注入块。
     *
     * @param agentCode 智能体编码（决定检索哪些知识库）
     * @param query     检索用的用户原始提问
     * @return 渲染好的注入块；不需要注入时返回 null 或空串
     */
    String retrieve(String agentCode, String query);

    /**
     * 带可信调用主体的检索入口。旧实现保持二参函数式接口兼容；需要文档 ACL 的实现覆写本方法。
     */
    default String retrieve(String agentCode, String query, AgentInvocationIdentity identity) {
        return retrieve(agentCode, query);
    }
}
