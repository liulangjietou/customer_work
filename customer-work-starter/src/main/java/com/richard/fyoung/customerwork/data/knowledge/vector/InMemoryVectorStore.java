package com.richard.fyoung.customerwork.data.knowledge.vector;

import com.richard.fyoung.customerwork.data.knowledge.VectorMath;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内向量存储：默认实现，供离线单测与开发期使用。
 *
 * <p>生产由具体实现覆盖（{@code @ConditionalOnMissingBean} 模式）。这里刻意保持朴素——
 * 它的职责是让链路在没有任何外部依赖时也能跑通并被断言，不是承担真实规模的检索。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public class InMemoryVectorStore implements VectorStore {

    /** namespace -> (chunkId -> 条目)。 */
    private final Map<String, Map<String, Entry>> data = new ConcurrentHashMap<>();

    /** 写入或覆盖一条向量。 */
    public void upsert(String namespace, String chunkId, String partition, float[] vector) {
        data.computeIfAbsent(namespace, k -> new ConcurrentHashMap<>())
            .put(chunkId, new Entry(partition, vector));
    }

    /** 删除一条向量。 */
    public void delete(String namespace, String chunkId) {
        Map<String, Entry> ns = data.get(namespace);
        if (ns != null) {
            ns.remove(chunkId);
        }
    }

    /** 清空一个命名空间。 */
    public void clear(String namespace) {
        data.remove(namespace);
    }

    @Override
    public List<VectorMatch> search(VectorQuery query) {
        if (query == null || query.hasNoPartition()) {
            return List.of();
        }
        Map<String, Entry> ns = data.get(query.namespace());
        if (ns == null || ns.isEmpty()) {
            return List.of();
        }
        List<VectorMatch> matches = new ArrayList<>();
        for (Map.Entry<String, Entry> e : ns.entrySet()) {
            Entry entry = e.getValue();
            if (!query.partitions().contains(entry.partition())) {
                continue;
            }
            double score = VectorMath.cosine(query.vector(), entry.vector());
            if (score >= query.threshold()) {
                matches.add(new VectorMatch(e.getKey(), entry.partition(), score));
            }
        }
        matches.sort(Comparator.comparingDouble(VectorMatch::score).reversed());
        return matches.size() <= query.topN() ? List.copyOf(matches)
            : List.copyOf(matches.subList(0, query.topN()));
    }

    private record Entry(String partition, float[] vector) {
    }
}
