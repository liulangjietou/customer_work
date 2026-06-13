package com.example.customerwork.rag;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RAG 知识库的内存实现（对应特性「RAG」，实现框架 {@link Knowledge} 接口）。
 *
 * <p>采用基于字符重合度的轻量打分做召回（伪语义检索），纯内存、无外部依赖，
 * 因此可直接单测、开箱即用。挂到 {@code ReActAgent.builder().knowledge(...)} 后，
 * 框架会在 RAG 模式下自动检索并把命中片段注入上下文。</p>
 *
 * <p><b>生产替换</b>：换成框架内置的 {@code SimpleKnowledge}（百炼 Embedding + 向量库）
 * 或 {@code BailianKnowledge}（百炼企业知识库）即可获得真正的语义检索与重排，
 * 调用方无需改动——这正是 {@link Knowledge} 抽象的价值。</p>
 */
public class InMemoryKeywordKnowledge implements Knowledge {

    private static final Logger log = LoggerFactory.getLogger(InMemoryKeywordKnowledge.class);

    private final List<Document> documents = new CopyOnWriteArrayList<>();
    private final AtomicInteger idSeq = new AtomicInteger();
    private final int defaultTopK;

    public InMemoryKeywordKnowledge(int defaultTopK) {
        this.defaultTopK = Math.max(1, defaultTopK);
    }

    /** 便捷方法：用纯文本灌库（自动包成 Document）。 */
    public Mono<Void> addTexts(List<String> texts) {
        List<Document> docs = new ArrayList<>();
        for (String text : texts) {
            String docId = "doc-" + idSeq.incrementAndGet();
            DocumentMetadata meta = new DocumentMetadata(
                TextBlock.builder().text(text).build(), docId, docId + "-0");
            docs.add(new Document(meta));
        }
        return addDocuments(docs);
    }

    @Override
    public Mono<Void> addDocuments(List<Document> docs) {
        return Mono.fromRunnable(() -> {
            if (docs != null) {
                documents.addAll(docs);
                log.debug("[RAG] 知识库现有文档数={}", documents.size());
            }
        });
    }

    @Override
    public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
        return Mono.fromSupplier(() -> {
            if (query == null || query.isBlank() || documents.isEmpty()) {
                return List.<Document>of();
            }
            int limit = resolveLimit(config);
            return documents.stream()
                .map(doc -> {
                    int s = score(query, contentOf(doc));
                    doc.setScore((double) s);
                    return doc;
                })
                .filter(doc -> doc.getScore() != null && doc.getScore() > 0)
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(limit)
                .toList();
        });
    }

    private int resolveLimit(RetrieveConfig config) {
        if (config == null) {
            return defaultTopK;
        }
        try {
            int limit = config.getLimit();
            return limit > 0 ? limit : defaultTopK;
        } catch (Exception e) {
            return defaultTopK;
        }
    }

    private String contentOf(Document doc) {
        DocumentMetadata meta = doc.getMetadata();
        return meta == null ? "" : meta.getContentText();
    }

    /** 相关度打分：查询中去重字符出现在文档中的数量。 */
    private int score(String query, String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        int hit = 0;
        List<Character> seen = new ArrayList<>();
        for (char c : query.toCharArray()) {
            if (Character.isWhitespace(c) || seen.contains(c)) {
                continue;
            }
            seen.add(c);
            if (content.indexOf(c) >= 0) {
                hit++;
            }
        }
        return hit;
    }
}
