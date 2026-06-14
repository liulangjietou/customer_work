package com.richard.fyoung.customerwork.tool.backend;

import reactor.core.publisher.Mono;

/**
 * 知识库后端（扩展点）：对接你自己的 FAQ / 政策库（也可直接用 RAG 的 KnowledgeProvider）。
 * 默认 {@link MockKnowledgeBackend}；提供自定义 Bean 即可覆盖。
 * @author owlzhangfq@gmail.com
 */
public interface KnowledgeBackend {

    /** 按用户问题检索知识库，返回带来源标注的结果。 */
    Mono<String> searchKnowledge(String query);
}
