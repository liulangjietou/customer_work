package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.runtime;

import com.richard.fyoung.customerwork.data.rag.search.KnowledgeInjectionMiddleware;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeGapRecorder;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeRetrievalProvider;

/**
 * 知识库召回内容的瞬态注入中间件（admin 侧调用壳）：把 admin 的 {@link KnowledgeRetrievalService}
 * 适配成 starter 的 {@link KnowledgeRetrievalProvider}，注入时机与形态全部复用
 * {@link KnowledgeInjectionMiddleware}。
 *
 * <p><b>语义全部在父类</b>（都是踩坑换来的，改动前先读父类注释）：瞬态注入不进会话历史、
 * 每轮只检索一次（RuntimeContext 按 agentCode 隔离缓存）、阻塞检索走
 * {@code Schedulers.boundedElastic()}、召回内容经 {@code ContentSpotlighter} 隔离 +
 * 系统提示词幂等追加规则、检索失败绝不打断对话。</p>

 * <p><b>知识盲区埋点</b>：召回为空（且非检索故障）时经 {@link KnowledgeGapRecorder} 记一笔。
 * 这条路径此前没有埋点，导致后台侧的知识未命中完全统计不到——盲区判定在工具路径上靠
 * {@code KnowledgeBackend.isMiss} 的文案契约，而本路径根本不经过 {@code KnowledgeBackend}。</p>
 *
 * <p>本壳<b>按智能体实例构建</b>（{@code agentCode} 构建期绑定，见
 * {@code AdminAgentInstanceFactory#buildInnerReActAgent}），不是共享单例 Bean。</p>
 * @author owlzhangfq@gmail.com
 */
public class KnowledgeRetrievalMiddleware extends KnowledgeInjectionMiddleware {

    public KnowledgeRetrievalMiddleware(KnowledgeRetrievalService retrievalService, String agentCode,
                                       KnowledgeGapRecorder gapRecorder) {
        super(retrievalService, agentCode, gapRecorder);
    }
}
