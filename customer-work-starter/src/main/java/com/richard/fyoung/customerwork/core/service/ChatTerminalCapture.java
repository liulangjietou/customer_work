package com.richard.fyoung.customerwork.core.service;

import com.richard.fyoung.customerwork.core.dto.ChatTerminalEnvelope;
import com.richard.fyoung.customerwork.core.dto.ChatUsageSnapshot;
import com.richard.fyoung.customerwork.core.dto.KnowledgeCitation;
import com.richard.fyoung.customerwork.core.dto.TaskPlanItem;
import com.richard.fyoung.customerwork.data.chatlog.ChatAnswerEvidence;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeRetrievalSource;
import io.agentscope.core.rag.model.Document;
import java.math.BigDecimal;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.Msg;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单次订阅内的终止元数据采集器。
 *
 * <p>由单次订阅创建，通过 Reactor Context 和本轮原生 RuntimeContext 传递，不进入共享服务字段。
 * 模型事件可能同时穿过父、子 Agent 的中间件，故按 replyId 去重后再累计。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class ChatTerminalCapture {

    public static final String ERROR = "ERROR";
    public static final String CACHE_HIT = "CACHE_HIT";
    public static final String QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
    public static final String MODEL_STOP = "MODEL_STOP";

    private final Map<String, ChatUsageSnapshot> usageByReplyId = new ConcurrentHashMap<>();
    private final AtomicReference<String> finishReason = new AtomicReference<>();

    /**
     * 本轮用到的知识引用。
     *
     * <p>用并发容器而不是普通 List：RAGMode.AGENTIC 下检索是模型自己发起的<b>工具调用</b>，
     * 一轮里可能触发多次，而工具执行是否并行由 Toolkit 配置决定——这里不该依赖它当前是串行的。</p>
     */
    private final Queue<KnowledgeCitation> citations = new ConcurrentLinkedQueue<>();
    private final Queue<KnowledgeRetrievalSource> retrievalSources = new ConcurrentLinkedQueue<>();

    /** 只接受本次 Knowledge 调用返回的元数据；文本标记不会产生内部文档引用。 */
    public void acceptKnowledgeDocuments(List<Document> documents) {
        for (Document document : documents) {
            var metadata = document.getMetadata();
            if (metadata == null || metadata.getChunkId() == null || metadata.getChunkId().isBlank()) {
                continue;
            }
            Object saved = metadata.getPayloadValue(KnowledgeRetrievalSource.class.getName());
            if (saved instanceof KnowledgeRetrievalSource source) {
                retrievalSources.add(source);
                continue;
            }
            Object base = metadata.getPayloadValue("knowledgeBase");
            Double score = document.getScore();
            retrievalSources.add(new KnowledgeRetrievalSource(0, base instanceof String name ? name : "知识库",
                metadata.getDocId(), metadata.getChunkId(),
                score == null || !Double.isFinite(score) ? null : BigDecimal.valueOf(score), null));
        }
    }

    /** 捕获 AgentScope 事件；同一事件重复经过嵌套中间件时不会重复计量。 */
    public void accept(AgentEvent event) {
        if (event instanceof ModelCallEndEvent modelEnd && modelEnd.getUsage() != null) {
            String key = modelEnd.getReplyId() == null || modelEnd.getReplyId().isBlank()
                ? modelEnd.getId() : modelEnd.getReplyId();
            if (key == null || key.isBlank()) {
                key = "event-" + System.identityHashCode(modelEnd);
            }
            usageByReplyId.putIfAbsent(key, ChatUsageSnapshot.from(modelEnd.getUsage()));
        }
        if (event instanceof AgentResultEvent result) {
            Msg message = result.getResult();
            if (message != null && message.getGenerateReason() != null) {
                finishReason.set(message.getGenerateReason().name());
            }
        }
    }

    public void markError() {
        finishReason.set(ERROR);
    }

    /**
     * 本轮的任务清单，只保留<b>最后一次</b>写入。
     *
     * <p>模型每完成一项就会重写整份清单（框架 {@code todo_write} 的语义是全量覆盖），
     * 累加会得到同一件事的多个历史状态——用户要看的是"现在办到哪了"，不是变更流水。</p>
     */
    private final AtomicReference<List<TaskPlanItem>> taskPlan = new AtomicReference<>(List.of());

    /** 记录本轮的任务清单；后写覆盖先写。 */
    public void acceptTaskPlan(List<TaskPlanItem> items) {
        if (items != null && !items.isEmpty()) {
            taskPlan.set(List.copyOf(items));
        }
    }

    /** 本轮任务清单（模型没列过则为空）。 */
    public List<TaskPlanItem> taskPlan() {
        return taskPlan.get();
    }

    /** 记录本轮召回的知识来源；重复调用累加（一轮可能检索多次）。 */
    public void acceptCitations(List<KnowledgeCitation> found) {
        if (found != null && !found.isEmpty()) {
            citations.addAll(found);
        }
    }

    /** 实际检索元数据优先于兼容文本线索，按知识库、文档及分片一起去重。 */
    public List<KnowledgeCitation> citations() {
        Map<CitationKey, KnowledgeCitation> unique = new LinkedHashMap<>();
        for (KnowledgeRetrievalSource source : retrievalSources) {
            KnowledgeCitation citation = new KnowledgeCitation(source.knowledgeBaseName(), source.documentId(),
                source.chunkId(), source.score() == null ? null : source.score().doubleValue());
            unique.putIfAbsent(CitationKey.of(citation), citation);
        }
        for (KnowledgeCitation citation : citations) {
            unique.putIfAbsent(CitationKey.of(citation), citation);
        }
        return List.copyOf(unique.values());
    }

    public ChatUsageSnapshot usage() {
        return usageByReplyId.values().stream()
            .reduce(ChatUsageSnapshot.empty(), ChatUsageSnapshot::plus);
    }

    /**
     * 生成终止信封。缓存、配额与故障兜底不经过 Agent 事件流，统一在这里根据最终回复判定。
     */
    public ChatTerminalEnvelope envelope(String messageId, String reply, String traceId) {
        return new ChatTerminalEnvelope(messageId, resolveFinishReason(reply), usage(), traceId, citations(), taskPlan());
    }

    /** 本轮结束时冻结可持久化字段；用量与 trace 不进入客户历史消息。 */
    public ChatAnswerEvidence answerEvidence(String reply) {
        return new ChatAnswerEvidence(resolveFinishReason(reply), citations(), taskPlan(), List.copyOf(retrievalSources));
    }

    private record CitationKey(String knowledgeBase, String documentId, String chunkId) {
        private static CitationKey of(KnowledgeCitation citation) {
            return new CitationKey(citation.knowledgeBase(), citation.documentId(), citation.chunkId());
        }
    }

    private String resolveFinishReason(String reply) {
        String reason = finishReason.get();
        if (CustomerServiceService.QUOTA_EXCEEDED_REPLY.equals(reply)) {
            reason = QUOTA_EXCEEDED;
        } else if (CustomerServiceService.FALLBACK_REPLY.equals(reply)) {
            reason = ERROR;
        } else if (reason == null) {
            reason = usageByReplyId.isEmpty() ? CACHE_HIT : MODEL_STOP;
        }
        return reason;
    }
}
