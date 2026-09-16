package com.richard.fyoung.customerwork.data.rag.search;

import org.springframework.util.StringUtils;

/** 供注入和运营统计共同使用的检索事实，空正文不再替代执行结果。 */
public record KnowledgeRetrievalResult(String block, Status status) {

    public enum Status {
        HIT, MISS, SKIPPED, DEGRADED
    }

    /** 所有检索目标执行成功后，才可根据正文判断命中或未命中。 */
    public static KnowledgeRetrievalResult completed(String block) {
        return new KnowledgeRetrievalResult(block, StringUtils.hasText(block) ? Status.HIT : Status.MISS);
    }

    /** 没有可执行的检索目标或提问时，不产生未命中事实。 */
    public static KnowledgeRetrievalResult skipped() {
        return new KnowledgeRetrievalResult(null, Status.SKIPPED);
    }

    /** 检索不完整时仍可注入成功取得的内容，但不能据此判断知识缺失。 */
    public static KnowledgeRetrievalResult degraded(String block) {
        return new KnowledgeRetrievalResult(block, Status.DEGRADED);
    }
}
