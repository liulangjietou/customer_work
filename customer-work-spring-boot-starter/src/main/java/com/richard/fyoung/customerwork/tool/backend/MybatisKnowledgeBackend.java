package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.tool.backend.entity.KnowledgeDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.KnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库后端的 MyBatis-Plus 实现：FAQ 条目落库到 {@code cw_knowledge}，检索用 LIKE 关键词匹配
 * （keyword / title / content 任一命中即召回，取前 5 条）。
 *
 * <p>输出带来源标注的格式对齐 {@link MockKnowledgeBackend}；召回走 {@link KnowledgeMapper#search} 的 XML。
 * 本类由 starter 的 {@code ToolBackendConfig} 在 {@code tool-backend.mode=jdbc} 时装配（种子数据集中在
 * {@code customer-work-schema.sql}）。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisKnowledgeBackend implements KnowledgeBackend {

    private static final Logger log = LoggerFactory.getLogger(MybatisKnowledgeBackend.class);

    private final KnowledgeMapper knowledgeMapper;

    public MybatisKnowledgeBackend(KnowledgeMapper knowledgeMapper) {
        this.knowledgeMapper = knowledgeMapper;
    }

    @Override
    public Mono<String> searchKnowledge(String query) {
        return Mono.fromSupplier(() -> doSearch(query));
    }

    private String doSearch(String query) {
        try {
            List<KnowledgeDO> entries = knowledgeMapper.search(query);
            List<String> hits = new ArrayList<>();
            for (KnowledgeDO entry : entries) {
                hits.add("· " + entry.getContent() + "（来源：" + entry.getSource() + "）");
            }
            if (hits.isEmpty()) {
                return "未在知识库中检索到直接相关条目，建议结合上下文回答或转人工。";
            }
            return "知识库召回如下：\n" + String.join("\n", hits);
        } catch (Exception e) {
            log.error("knowledge search failed, code={}, query={}", "KNOWLEDGE-BACKEND-SEARCH-FAIL", query, e);
            return "知识库检索暂时不可用。";
        }
    }
}
