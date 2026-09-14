package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.tool.backend.mapper.KnowledgeMapper;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Mono;

/** 候选试用只读取冻结的 FAQ；存储异常单独保留，不能由模型的补偿话术掩盖为通过。 */
public final class SnapshotKnowledgeBackend implements KnowledgeBackend {
    private final KnowledgeMapper mapper;
    private final String snapshotJson;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Set<Long> recalledIds = ConcurrentHashMap.newKeySet();

    public SnapshotKnowledgeBackend(KnowledgeMapper mapper, String snapshotJson) {
        this.mapper = mapper;
        this.snapshotJson = snapshotJson;
    }

    @Override
    public Mono<String> searchKnowledge(String query) {
        return Mono.fromSupplier(() -> {
            var entries = mapper.searchSnapshot(query, snapshotJson);
            entries.forEach(entry -> recalledIds.add(entry.getId()));
            return KnowledgeRecallFormatter.format(entries);
        })
            .doOnError(error -> failure.compareAndSet(null, error));
    }

    /** 目标问题的通过判定还需证明候选正文实际进入过该次工具返回。 */
    public boolean recalled(long rowId) {
        return recalledIds.contains(rowId);
    }

    /** 工具框架可能将异常转换为消息；评测结束仍须主动核验，禁止将检索故障计为有效答复。 */
    public void requireSuccessfulRetrieval() {
        Throwable error = failure.get();
        if (error != null) throw new IllegalStateException("candidate knowledge retrieval failed", error);
    }
}
