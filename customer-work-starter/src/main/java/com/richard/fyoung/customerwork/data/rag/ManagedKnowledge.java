package com.richard.fyoung.customerwork.data.rag;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customerwork.data.knowledge.embedding.EmbeddingClient;
import com.richard.fyoung.customerwork.data.knowledge.entity.KnowledgeChunkDO;
import com.richard.fyoung.customerwork.data.knowledge.entity.KnowledgeVersionDO;
import com.richard.fyoung.customerwork.data.knowledge.mapper.KnowledgeChunkMapper;
import com.richard.fyoung.customerwork.data.knowledge.mapper.KnowledgeVersionMapper;
import com.richard.fyoung.customerwork.data.knowledge.vector.VectorMatch;
import com.richard.fyoung.customerwork.data.knowledge.vector.VectorQuery;
import com.richard.fyoung.customerwork.data.knowledge.vector.VectorStore;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 受管知识库：让客服端真正用上后台维护的那套企业知识库。
 *
 * <p><b>它解决的问题</b>：此前客服端与后台是两套互不相通的知识栈。C 端建 Agent 时挂的是
 * {@code KnowledgeProvider}，生产默认落到内置的 4 条演示文本；而运营在后台做的版本管理、
 * ACL、新鲜度门禁、同步任务，对线上真实对话<b>零影响</b>——看板会让人以为知识库在工作，
 * 用户那边一问三不知。这是本项目"能力只接在用户不走的那条路上"的又一次复发，
 * 而且接反了方向：接在了内部员工用的后台工作台上。</p>
 *
 * <p><b>只放行 PUBLIC</b>：终端用户不是内部员工，没有可用于细粒度 ACL 判定的身份。
 * 把非公开文档放进检索范围等于让任何人都能问出内部资料，因此这条约束落在 SQL 里
 * （{@code selectPublicPartitions}），不依赖调用方记得过滤。</p>
 *
 * <p><b>维度不一致直接跳过该版本</b>：查询向量由当前配置的 embedding 模型产生，
 * 而分片向量是投影当时那个模型产生的。两者维度不同就没有可比性——
 * 算出来的分数不是"不太像"，而是毫无意义。换 embedding 模型时新旧并存正是这种情况。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public class ManagedKnowledge implements Knowledge {

    private static final Logger log = LoggerFactory.getLogger(ManagedKnowledge.class);

    private final VectorStore vectorStore;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeVersionMapper versionMapper;
    private final EmbeddingClient embeddingClient;
    private final List<String> knowledgeBaseCodes;

    public ManagedKnowledge(VectorStore vectorStore,
                            KnowledgeChunkMapper chunkMapper,
                            KnowledgeVersionMapper versionMapper,
                            EmbeddingClient embeddingClient,
                            List<String> knowledgeBaseCodes) {
        this.vectorStore = vectorStore;
        this.chunkMapper = chunkMapper;
        this.versionMapper = versionMapper;
        this.embeddingClient = embeddingClient;
        this.knowledgeBaseCodes = knowledgeBaseCodes == null ? List.of() : List.copyOf(knowledgeBaseCodes);
    }

    /**
     * 客服端不接受运行时写入。
     *
     * <p>知识的唯一写入口是后台的同步任务经跨库门面投影过来——那里才有版本、审核与 lineage。
     * 从对话链路往知识库里塞内容会绕开全部治理，因此这里直接拒绝而不是静默忽略。</p>
     */
    @Override
    public Mono<Void> addDocuments(List<Document> docs) {
        return Mono.error(new UnsupportedOperationException(
            "受管知识库只接受后台投影写入，对话链路不得直接写入知识"));
    }

    @Override
    public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
        if (query == null || query.isBlank()) {
            return Mono.just(List.of());
        }
        // 查库与 embedding 都是阻塞调用，必须挪出响应式线程
        return Mono.fromCallable(() -> doRetrieve(query, config))
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(e -> {
                log.error("managed knowledge retrieve failed, code={} query={}",
                    "KB-MANAGED-RETRIEVE-FAIL", abbreviate(query), e);
                // 检索失败不该让整轮对话崩掉，退化成"没查到"由模型自行应对
                return Mono.just(List.of());
            });
    }

    private List<Document> doRetrieve(String query, RetrieveConfig config) {
        List<KnowledgeVersionDO> versions = resolveVersions();
        if (versions.isEmpty()) {
            return List.of();
        }
        float[] queryVector = embeddingClient.embedQuery(query);
        if (queryVector == null || queryVector.length == 0) {
            return List.of();
        }

        int limit = config != null && config.getLimit() > 0 ? config.getLimit() : 3;
        List<Scored> all = new ArrayList<>();
        for (KnowledgeVersionDO version : versions) {
            if (version.getDimensions() != null && version.getDimensions() != queryVector.length) {
                log.error("knowledge version dimension mismatch, code={} kbVersionId={} "
                        + "versionDim={} queryDim={}", "KB-VERSION-DIM-MISMATCH",
                    version.getKbVersionId(), version.getDimensions(), queryVector.length);
                continue;
            }
            all.addAll(searchVersion(version, queryVector, limit, config));
        }
        if (all.isEmpty()) {
            return List.of();
        }
        all.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<Scored> top = all.size() <= limit ? all : all.subList(0, limit);
        return materialize(top);
    }

    private List<Scored> searchVersion(KnowledgeVersionDO version, float[] queryVector,
                                       int limit, RetrieveConfig config) {
        List<Long> partitions = chunkMapper.selectPublicPartitions(version.getKbVersionId());
        if (partitions == null || partitions.isEmpty()) {
            return List.of();
        }
        double threshold = resolveThreshold(version, config);
        int topN = version.getTopN() != null && version.getTopN() > 0
            ? Math.max(version.getTopN(), limit) : limit;
        List<VectorMatch> matches = vectorStore.search(new VectorQuery(
            String.valueOf(version.getKbVersionId()),
            partitions.stream().map(String::valueOf).toList(),
            queryVector, topN, threshold));
        return matches.stream().map(m -> new Scored(version, m, m.score())).toList();
    }

    /** 版本自带阈值与调用方传入的阈值取较严格的一个：两者都是"不要低质量召回"的表达。 */
    private double resolveThreshold(KnowledgeVersionDO version, RetrieveConfig config) {
        double versionThreshold = version.getScoreThreshold() == null
            ? 0d : version.getScoreThreshold().doubleValue();
        double configThreshold = config == null ? 0d : config.getScoreThreshold();
        return Math.max(versionThreshold, configThreshold);
    }

    /** 命中确定后才按 id 回查正文——打分阶段刻意不读它。 */
    private List<Document> materialize(List<Scored> top) {
        List<Long> ids = top.stream()
            .map(s -> Long.valueOf(s.match().chunkId()))
            .toList();
        Map<Long, KnowledgeChunkDO> byId = chunkMapper.selectByIds(ids).stream()
            .collect(Collectors.toMap(KnowledgeChunkDO::getId, c -> c, (a, b) -> a, HashMap::new));

        List<Document> documents = new ArrayList<>(top.size());
        for (Scored scored : top) {
            KnowledgeChunkDO chunk = byId.get(Long.valueOf(scored.match().chunkId()));
            if (chunk == null) {
                continue;
            }
            String docId = chunk.getExternalId() != null && !chunk.getExternalId().isBlank()
                ? chunk.getExternalId() : String.valueOf(chunk.getDocRevisionId());
            DocumentMetadata metadata = new DocumentMetadata(
                TextBlock.builder().text(chunk.getContent()).build(),
                docId,
                String.valueOf(chunk.getId()),
                // 来源信息随文档带出，供回答里的引用标注使用
                Map.of("knowledgeBase", scored.version().getKbName(),
                    "kbCode", scored.version().getKbCode(),
                    "chunkIndex", chunk.getChunkIndex()));
            Document document = new Document(metadata);
            document.setScore(scored.score());
            documents.add(document);
        }
        return List.copyOf(documents);
    }

    /** 解析本租户可用的知识库版本；配置了编码则只取这些，否则取该租户全部已投影版本。 */
    private List<KnowledgeVersionDO> resolveVersions() {
        QueryWrapper<KnowledgeVersionDO> wrapper = new QueryWrapper<>();
        if (!knowledgeBaseCodes.isEmpty()) {
            wrapper.in("kb_code", knowledgeBaseCodes);
        }
        List<KnowledgeVersionDO> versions = versionMapper.selectList(wrapper);
        return versions == null ? List.of() : versions;
    }

    private String abbreviate(String text) {
        return text.length() <= 60 ? text : text.substring(0, 60) + "...";
    }

    private record Scored(KnowledgeVersionDO version, VectorMatch match, double score) {
    }
}
