package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.client.KnowledgeSearchClient;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiAgentKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersion;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiAgentKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseVersionMapper;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeBaseEndpoint;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeNode;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeRetrievalProvider;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 按 Agent 绑定的不可变知识库版本检索：托管文档走本地向量与 ACL，外部 RAG 走版本冻结的连接参数。
 * 检索只是旁路增强，唯一异常防御点收敛在 {@link #retrieve(String, String, AgentInvocationIdentity)}。
 */
@Component
public class KnowledgeRetrievalService implements KnowledgeRetrievalProvider {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrievalService.class);
    private static final String CODE_RETRIEVAL_FAIL = "RAG-RETRIEVAL-FAIL";
    private static final String BLOCK_OPEN = "<retrieved_knowledge>";
    private static final String BLOCK_CLOSE = "</retrieved_knowledge>";
    private static final String BLOCK_HINT =
        "以下是根据用户本轮提问从知识库检索到的参考资料。请优先依据这些资料作答，"
            + "引用时标注对应编号；若资料与问题无关，忽略即可，不要强行使用。";
    private static final int DEFAULT_TOP_N = 5;

    private final AiAgentMapper agentMapper;
    private final AiAgentKnowledgeBaseMapper agentKnowledgeBaseMapper;
    private final AiKnowledgeBaseMapper knowledgeBaseMapper;
    private final AiKnowledgeBaseVersionMapper versionMapper;
    private final AesGcmCryptoUtil cryptoUtil;
    private final KnowledgeSearchClient searchClient;
    private final ManagedKnowledgeSearchService managedSearchService;

    public KnowledgeRetrievalService(AiAgentMapper agentMapper,
                                     AiAgentKnowledgeBaseMapper agentKnowledgeBaseMapper,
                                     AiKnowledgeBaseMapper knowledgeBaseMapper,
                                     AiKnowledgeBaseVersionMapper versionMapper,
                                     AesGcmCryptoUtil cryptoUtil,
                                     KnowledgeSearchClient searchClient,
                                     ManagedKnowledgeSearchService managedSearchService) {
        this.agentMapper = agentMapper;
        this.agentKnowledgeBaseMapper = agentKnowledgeBaseMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.versionMapper = versionMapper;
        this.cryptoUtil = cryptoUtil;
        this.searchClient = searchClient;
        this.managedSearchService = managedSearchService;
    }

    @Override
    public String retrieve(String agentCode, String query) {
        return retrieve(agentCode, query, null);
    }

    @Override
    public String retrieve(String agentCode, String query, AgentInvocationIdentity identity) {
        if (!StringUtils.hasText(agentCode) || !StringUtils.hasText(query)) {
            return null;
        }
        try {
            RetrievalTargets targets = resolveTargets(agentCode);
            if (targets.empty()) {
                return null;
            }
            List<KnowledgeNode> nodes = new ArrayList<>();
            if (!targets.externalEndpoints().isEmpty()) {
                nodes.addAll(searchClient.searchAll(targets.externalEndpoints(), query));
            }
            for (ManagedTarget target : targets.managedTargets()) {
                nodes.addAll(managedSearchService.search(target.knowledgeBaseName(), target.version(),
                    query, identity));
            }
            if (CollectionUtils.isEmpty(nodes)) {
                log.info("[rag] retrieval hit nothing, agentCode={}, targetCount={}",
                    agentCode, targets.count());
                return null;
            }
            List<KnowledgeNode> ranked = nodes.stream()
                .sorted(Comparator.comparing(KnowledgeNode::score).reversed())
                .limit(targets.maxTopN())
                .toList();
            log.info("[rag] retrieval hit, agentCode={}, targetCount={}, nodeCount={}",
                agentCode, targets.count(), ranked.size());
            return renderBlock(ranked);
        } catch (Exception e) {
            log.error("[rag] retrieval failed, code={}, agentCode={}", CODE_RETRIEVAL_FAIL, agentCode, e);
            return null;
        }
    }

    private RetrievalTargets resolveTargets(String agentCode) {
        AiAgent agent = agentMapper.selectOne(
            new LambdaQueryWrapper<AiAgent>().eq(AiAgent::getAgentCode, agentCode));
        if (agent == null) {
            return RetrievalTargets.EMPTY;
        }
        List<AiAgentKnowledgeBase> relations = agentKnowledgeBaseMapper.selectList(
            new LambdaQueryWrapper<AiAgentKnowledgeBase>()
                .eq(AiAgentKnowledgeBase::getAgentId, agent.getId()));
        if (CollectionUtils.isEmpty(relations)) {
            return RetrievalTargets.EMPTY;
        }
        List<Long> knowledgeBaseIds = relations.stream()
            .map(AiAgentKnowledgeBase::getKnowledgeBaseId).distinct().toList();
        Map<Long, AiKnowledgeBase> knowledgeBases = knowledgeBaseMapper.selectBatchIds(knowledgeBaseIds)
            .stream().collect(Collectors.toMap(AiKnowledgeBase::getId, Function.identity()));
        List<Long> versionIds = relations.stream()
            .map(AiAgentKnowledgeBase::getKnowledgeBaseVersionId).filter(Objects::nonNull).distinct().toList();
        Map<Long, AiKnowledgeBaseVersion> versions = versionMapper == null || versionIds.isEmpty()
            ? Map.of() : versionMapper.selectBatchIds(versionIds).stream()
                .collect(Collectors.toMap(AiKnowledgeBaseVersion::getId, Function.identity()));

        List<KnowledgeBaseEndpoint> external = new ArrayList<>();
        List<ManagedTarget> managed = new ArrayList<>();
        int maxTopN = DEFAULT_TOP_N;
        for (AiAgentKnowledgeBase relation : relations) {
            AiKnowledgeBase knowledgeBase = knowledgeBases.get(relation.getKnowledgeBaseId());
            if (knowledgeBase == null
                || !Integer.valueOf(StatusFlags.ENABLED).equals(knowledgeBase.getStatus())) {
                continue;
            }
            Long versionId = relation.getKnowledgeBaseVersionId();
            AiKnowledgeBaseVersion version = versionId == null ? null : versions.get(versionId);
            if (version == null) {
                // V91 会回填所有关系；该分支只为滚动升级窗口和旧单测保留。
                if (relation.getKnowledgeBaseVersionId() == null
                    && Integer.valueOf(ConnectivityTestStatus.SUCCESS).equals(knowledgeBase.getTestStatus())) {
                    external.add(toEndpoint(knowledgeBase));
                    maxTopN = Math.max(maxTopN, normalizeTopN(knowledgeBase.getTopN()));
                }
                continue;
            }
            if (!Objects.equals(version.getKnowledgeBaseId(), knowledgeBase.getId())) {
                continue;
            }
            maxTopN = Math.max(maxTopN, normalizeTopN(version.getTopN()));
            if (version.getDocumentCount() != null && version.getDocumentCount() > 0) {
                managed.add(new ManagedTarget(knowledgeBase.getKbName(), version));
            } else {
                external.add(toEndpoint(knowledgeBase, version));
            }
        }
        return new RetrievalTargets(List.copyOf(external), List.copyOf(managed), maxTopN);
    }

    private KnowledgeBaseEndpoint toEndpoint(AiKnowledgeBase knowledgeBase) {
        return new KnowledgeBaseEndpoint(knowledgeBase.getId(), knowledgeBase.getKbName(),
            knowledgeBase.getBaseUrl(), knowledgeBase.getAppId(),
            cryptoUtil.decrypt(knowledgeBase.getApiKey()), knowledgeBase.getContentType(),
            knowledgeBase.getExtraHeaders(), knowledgeBase.getTopN(), knowledgeBase.getScoreThreshold());
    }

    private KnowledgeBaseEndpoint toEndpoint(AiKnowledgeBase knowledgeBase,
                                              AiKnowledgeBaseVersion version) {
        return new KnowledgeBaseEndpoint(knowledgeBase.getId(), knowledgeBase.getKbName(),
            version.getBaseUrl(), version.getAppId(), cryptoUtil.decrypt(version.getApiKey()),
            version.getContentType(), version.getExtraHeaders(), version.getTopN(),
            version.getScoreThreshold());
    }

    private int normalizeTopN(Integer topN) {
        return topN == null || topN <= 0 ? DEFAULT_TOP_N : topN;
    }

    private String renderBlock(List<KnowledgeNode> nodes) {
        StringBuilder builder = new StringBuilder(BLOCK_OPEN).append('\n').append(BLOCK_HINT).append('\n');
        for (int i = 0; i < nodes.size(); i++) {
            KnowledgeNode node = nodes.get(i);
            builder.append('\n')
                .append('[').append(i + 1).append("] ")
                .append("knowledge_base=").append(node.kbName())
                .append(" doc_id=").append(node.docId())
                .append(" chunk_id=").append(node.chunkId())
                .append(" score=").append(node.score().toPlainString())
                .append('\n').append(node.content()).append('\n');
        }
        return builder.append(BLOCK_CLOSE).toString();
    }

    private record ManagedTarget(String knowledgeBaseName, AiKnowledgeBaseVersion version) {
    }

    private record RetrievalTargets(List<KnowledgeBaseEndpoint> externalEndpoints,
                                    List<ManagedTarget> managedTargets,
                                    int maxTopN) {
        private static final RetrievalTargets EMPTY =
            new RetrievalTargets(List.of(), List.of(), DEFAULT_TOP_N);

        private boolean empty() {
            return externalEndpoints.isEmpty() && managedTargets.isEmpty();
        }

        private int count() {
            return externalEndpoints.size() + managedTargets.size();
        }
    }
}
