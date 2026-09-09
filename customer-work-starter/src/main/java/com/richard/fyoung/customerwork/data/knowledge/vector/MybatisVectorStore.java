package com.richard.fyoung.customerwork.data.knowledge.vector;

import com.richard.fyoung.customerwork.data.knowledge.VectorMath;
import com.richard.fyoung.customerwork.data.knowledge.entity.ChunkVectorDO;
import com.richard.fyoung.customerwork.data.knowledge.mapper.KnowledgeChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 基于 MySQL 的向量检索实现。
 *
 * <p><b>相比它取代的那版实现改了三件事</b>：</p>
 * <ol>
 *   <li><b>不读正文</b>：打分阶段的投影只有 id / 分区 / 向量。参与打分的可能是上万条分片，
 *       而最终只有 topN 的正文会被用到，此前那版把 {@code content}（LONGTEXT）一起 selectList
 *       出来，其余全部白读——40MB 里的大头正是这些正文；</li>
 *   <li><b>不解析 JSON</b>：向量以定长 float32 存 {@code VARBINARY}，读取是一次
 *       {@code ByteBuffer} 顺序扫描，没有 {@code ObjectMapper.readValue}；</li>
 *   <li><b>不全量进内存</b>：按主键分批拉取、边拉边打分，堆里只留 topN。
 *       此前是一次性 {@code selectList} 无 limit 无分页，知识库一扩容就是 OOM 与连接池耗尽的事故点。</li>
 * </ol>
 *
 * <p>它仍然是「应用层算余弦」，只是把常数项压到了合理范围。真正的量级问题要靠专用向量库解决，
 * 那由 {@link VectorStore} 这层抽象留出替换位——换实现时调用方不用动。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public class MybatisVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisVectorStore.class);

    /** 每批拉取的分片数：够摊薄往返开销，又不至于让单批驻留内存过大。 */
    private static final int DEFAULT_BATCH_SIZE = 500;

    /**
     * 单次检索最多扫描的分片数。
     *
     * <p>没有这个上限的话，一个超大知识库版本会让单次提问把整张表扫一遍。到达上限时记 error 日志
     * 并返回已经算出的 topN——<b>宁可给出一个不完整但可用的结果，也不要让用户那一轮对话卡死</b>，
     * 同时把"该换向量库了"这件事以可检索的错误码留在日志里。</p>
     */
    private static final int MAX_SCANNED_CHUNKS = 50_000;

    private final KnowledgeChunkMapper chunkMapper;
    private final int batchSize;

    public MybatisVectorStore(KnowledgeChunkMapper chunkMapper) {
        this(chunkMapper, DEFAULT_BATCH_SIZE);
    }

    MybatisVectorStore(KnowledgeChunkMapper chunkMapper, int batchSize) {
        this.chunkMapper = chunkMapper;
        this.batchSize = batchSize;
    }

    @Override
    public List<VectorMatch> search(VectorQuery query) {
        if (query == null || query.hasNoPartition()) {
            // 权限过滤后无可查分区，语义是"查不到"；落成全量检索就是越权
            return List.of();
        }
        float[] queryVector = query.vector();
        if (queryVector == null || queryVector.length == 0) {
            return List.of();
        }
        Long kbVersionId = parseVersionId(query.namespace());
        if (kbVersionId == null) {
            return List.of();
        }
        Set<Long> partitions = query.partitions().stream()
            .map(MybatisVectorStore::parseLong)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
        if (partitions.isEmpty()) {
            return List.of();
        }

        // 小顶堆：堆顶是当前 topN 里最差的一条，来了更好的就替换掉它
        PriorityQueue<VectorMatch> heap =
            new PriorityQueue<>(Comparator.comparingDouble(VectorMatch::score));

        long afterId = 0L;
        int scanned = 0;
        int dimensionMismatch = 0;
        while (scanned < MAX_SCANNED_CHUNKS) {
            List<ChunkVectorDO> batch =
                chunkMapper.selectVectorsByPartitions(kbVersionId, partitions, afterId, batchSize);
            if (batch == null || batch.isEmpty()) {
                break;
            }
            for (ChunkVectorDO row : batch) {
                afterId = Math.max(afterId, row.getId());
                scanned++;
                float[] vector = VectorCodec.decode(row.getEmbedding());
                if (vector.length != queryVector.length) {
                    // 维度不一致算出来的分数没有意义，必须跳过而不是当成一条低分命中
                    dimensionMismatch++;
                    continue;
                }
                double score = VectorMath.cosine(queryVector, vector);
                if (score < query.threshold()) {
                    continue;
                }
                if (heap.size() < query.topN()) {
                    heap.offer(new VectorMatch(String.valueOf(row.getId()),
                        String.valueOf(row.getDocRevisionId()), score));
                } else if (heap.peek() != null && score > heap.peek().score()) {
                    heap.poll();
                    heap.offer(new VectorMatch(String.valueOf(row.getId()),
                        String.valueOf(row.getDocRevisionId()), score));
                }
            }
            if (batch.size() < batchSize) {
                break;
            }
        }

        if (dimensionMismatch > 0) {
            log.error("knowledge vector dimension mismatch, code={} kbVersionId={} queryDim={} skipped={}",
                "KB-VECTOR-DIM-MISMATCH", kbVersionId, queryVector.length, dimensionMismatch);
        }
        if (scanned >= MAX_SCANNED_CHUNKS) {
            log.error("knowledge vector scan hit cap, code={} kbVersionId={} scanned={} —— "
                    + "result may be incomplete, consider migrating to a dedicated vector store",
                "KB-VECTOR-SCAN-CAP", kbVersionId, scanned);
        }

        List<VectorMatch> matches = new ArrayList<>(heap);
        matches.sort(Comparator.comparingDouble(VectorMatch::score).reversed());
        return List.copyOf(matches);
    }

    /** 命名空间即知识库版本 ID。 */
    private Long parseVersionId(String namespace) {
        Long parsed = parseLong(namespace);
        if (parsed == null) {
            log.error("illegal knowledge vector namespace, code={} namespace={}",
                "KB-VECTOR-NAMESPACE-ILLEGAL", namespace);
        }
        return parsed;
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
