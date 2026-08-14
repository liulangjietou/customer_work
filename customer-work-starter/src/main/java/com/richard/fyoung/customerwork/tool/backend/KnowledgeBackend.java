package com.richard.fyoung.customerwork.tool.backend;

import reactor.core.publisher.Mono;

/**
 * 知识库后端（扩展点）：对接你自己的 FAQ / 政策库（也可直接用 RAG 的 KnowledgeProvider）。
 * 默认 {@link MockKnowledgeBackend}；提供自定义 Bean 即可覆盖。
 * @author owlzhangfq@gmail.com
 */
public interface KnowledgeBackend {

    /**
     * 未命中时的统一回复。
     *
     * <p>提到接口上是因为它已经成了<b>契约的一部分</b>：知识盲区分析要据此判断"这次检索没查到"。
     * 此前两个实现各自硬编码同一句话，是隐式约定——任何一方改了文案，盲区统计就会静默失效，
     * 且没有任何测试会发现。提成常量后，改文案不再能悄悄破坏判定。</p>
     */
    String NO_HIT_REPLY = "未在知识库中检索到直接相关条目，建议结合上下文回答或转人工。";

    /** 按用户问题检索知识库，返回带来源标注的结果；未命中返回 {@link #NO_HIT_REPLY}。 */
    Mono<String> searchKnowledge(String query);

    /** 判断一次检索是否未命中（知识盲区统计据此埋点）。 */
    static boolean isMiss(String result) {
        return NO_HIT_REPLY.equals(result);
    }
}
