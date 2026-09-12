package com.richard.fyoung.customerwork.data.rag.search;

import java.util.List;
import org.springframework.util.StringUtils;

/** 供注入和运营统计共同使用的检索事实，空正文不再替代执行结果。 */
public record KnowledgeRetrievalResult(String block, Status status, List<KnowledgeRetrievalSource> sources) {

    public KnowledgeRetrievalResult {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** 兼容只提供文本与状态的调用方；缺少来源时不推测内部版本标识。 */
    public KnowledgeRetrievalResult(String block, Status status) {
        this(block, status, List.of());
    }

    public enum Status {
        HIT, MISS, SKIPPED, DEGRADED
    }

    /** 所有检索目标执行成功后，才可根据正文判断命中或未命中。 */
    public static KnowledgeRetrievalResult completed(String block) {
        return completed(block, List.of());
    }

    /** 来源必须对应最终注入的节点集合。 */
    public static KnowledgeRetrievalResult completed(String block, List<KnowledgeRetrievalSource> sources) {
        return new KnowledgeRetrievalResult(block, StringUtils.hasText(block) ? Status.HIT : Status.MISS, sources);
    }

    /** 没有可执行的检索目标或提问时，不产生未命中事实。 */
    public static KnowledgeRetrievalResult skipped() {
        return new KnowledgeRetrievalResult(null, Status.SKIPPED);
    }

    /** 检索不完整时仍可注入成功取得的内容，但不能据此判断知识缺失。 */
    public static KnowledgeRetrievalResult degraded(String block) {
        return degraded(block, List.of());
    }

    /** 部分成功的来源仍然可追溯，整体状态保持降级。 */
    public static KnowledgeRetrievalResult degraded(String block, List<KnowledgeRetrievalSource> sources) {
        return new KnowledgeRetrievalResult(block, Status.DEGRADED, sources);
    }
}
