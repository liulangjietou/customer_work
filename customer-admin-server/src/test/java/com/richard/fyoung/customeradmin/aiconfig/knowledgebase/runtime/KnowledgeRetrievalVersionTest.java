package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.runtime;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.client.KnowledgeSearchClient;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiAgentKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersion;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiAgentKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseVersionMapper;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeBaseEndpoint;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalVersionTest {

    private static final String SECRET_KEY = "0123456789abcdef";

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        Configuration configuration = new Configuration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AiAgent.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AiAgentKnowledgeBase.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AiKnowledgeBase.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AiKnowledgeBaseVersion.class);
    }

    @Test
    void externalRetrieval_shouldUseVersionFrozenConnection_notMutableKnowledgeBaseFields() {
        AiAgentMapper agentMapper = mock(AiAgentMapper.class);
        AiAgentKnowledgeBaseMapper relationMapper = mock(AiAgentKnowledgeBaseMapper.class);
        AiKnowledgeBaseMapper knowledgeBaseMapper = mock(AiKnowledgeBaseMapper.class);
        AiKnowledgeBaseVersionMapper versionMapper = mock(AiKnowledgeBaseVersionMapper.class);
        KnowledgeSearchClient searchClient = mock(KnowledgeSearchClient.class);
        AesGcmCryptoUtil crypto = new AesGcmCryptoUtil(SECRET_KEY);
        KnowledgeRetrievalService service = new KnowledgeRetrievalService(agentMapper, relationMapper,
            knowledgeBaseMapper, versionMapper, crypto, searchClient, mock(ManagedKnowledgeSearchService.class));

        AiAgent agent = new AiAgent();
        agent.setId(1L);
        AiAgentKnowledgeBase relation = new AiAgentKnowledgeBase();
        relation.setAgentId(1L);
        relation.setKnowledgeBaseId(7L);
        relation.setKnowledgeBaseVersionId(91L);
        AiKnowledgeBase mutable = new AiKnowledgeBase();
        mutable.setId(7L);
        mutable.setKbName("产品知识库");
        mutable.setStatus(1);
        mutable.setBaseUrl("https://new.example.test");
        mutable.setApiKey(crypto.encrypt("new-key"));
        AiKnowledgeBaseVersion frozen = new AiKnowledgeBaseVersion();
        frozen.setId(91L);
        frozen.setKnowledgeBaseId(7L);
        frozen.setBaseUrl("https://frozen.example.test");
        frozen.setAppId("frozen-app");
        frozen.setApiKey(crypto.encrypt("frozen-key"));
        frozen.setContentType("application/json");
        frozen.setExtraHeaders("");
        frozen.setTopN(3);
        frozen.setScoreThreshold(new BigDecimal("0.100000"));
        frozen.setDocumentCount(0);

        when(agentMapper.selectOne(any())).thenReturn(agent);
        when(relationMapper.selectList(any())).thenReturn(List.of(relation));
        when(knowledgeBaseMapper.selectBatchIds(any())).thenReturn(List.of(mutable));
        when(versionMapper.selectBatchIds(any())).thenReturn(List.of(frozen));
        when(searchClient.searchAll(any(), any())).thenReturn(List.of());

        assertNull(service.retrieve("agent-a", "问题"));

        verify(searchClient).searchAll(List.of(new KnowledgeBaseEndpoint(7L, "产品知识库",
            "https://frozen.example.test", "frozen-app", "frozen-key", "application/json", "", 3,
            new BigDecimal("0.100000"))), "问题");
    }
}
