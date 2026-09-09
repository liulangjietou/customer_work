package com.richard.fyoung.customerwork.data.rag;

import com.richard.fyoung.customerwork.data.knowledge.embedding.EmbeddingClient;
import com.richard.fyoung.customerwork.data.knowledge.entity.KnowledgeChunkDO;
import com.richard.fyoung.customerwork.data.knowledge.entity.KnowledgeVersionDO;
import com.richard.fyoung.customerwork.data.knowledge.mapper.KnowledgeChunkMapper;
import com.richard.fyoung.customerwork.data.knowledge.mapper.KnowledgeVersionMapper;
import com.richard.fyoung.customerwork.data.knowledge.vector.VectorMatch;
import com.richard.fyoung.customerwork.data.knowledge.vector.VectorQuery;
import com.richard.fyoung.customerwork.data.knowledge.vector.VectorStore;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 受管知识库检索测试。
 *
 * <p><b>守的是什么</b>：客服端与后台此前是两套互不相通的知识栈——运营在后台维护的知识库
 * 对线上真实对话零影响，而 C 端只能查到内置的 4 条演示文本。这条链路是把两者接上的那一条。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class ManagedKnowledgeTest {

    private KnowledgeVersionDO version(long id, int dimensions) {
        KnowledgeVersionDO v = new KnowledgeVersionDO();
        v.setKbVersionId(id);
        v.setKbCode("kb-faq");
        v.setKbName("售后FAQ");
        v.setTopN(3);
        v.setScoreThreshold(BigDecimal.ZERO);
        v.setDimensions(dimensions);
        return v;
    }

    private KnowledgeChunkDO chunk(long id, String content) {
        KnowledgeChunkDO c = new KnowledgeChunkDO();
        c.setId(id);
        c.setDocRevisionId(10L);
        c.setChunkIndex(0);
        c.setContent(content);
        c.setExternalId("doc-a");
        return c;
    }

    private EmbeddingClient embedding(int dimensions) {
        EmbeddingClient client = mock(EmbeddingClient.class);
        when(client.embedQuery(any())).thenReturn(new float[dimensions]);
        when(client.dimensions()).thenReturn(dimensions);
        when(client.modelName()).thenReturn("text-embedding-v3");
        return client;
    }

    @Test
    @DisplayName("命中后回查正文并带出来源信息")
    void retrievesAndMaterializesContent() {
        VectorStore store = mock(VectorStore.class);
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeVersionMapper versionMapper = mock(KnowledgeVersionMapper.class);

        when(versionMapper.selectList(any())).thenReturn(List.of(version(1L, 4)));
        when(chunkMapper.selectPublicPartitions(1L)).thenReturn(List.of(10L));
        when(store.search(any())).thenReturn(List.of(new VectorMatch("100", "10", 0.92d)));
        when(chunkMapper.selectByIds(any())).thenReturn(List.of(chunk(100L, "七天无理由从签收次日算起")));

        List<Document> docs = new ManagedKnowledge(store, chunkMapper, versionMapper,
            embedding(4), List.of()).retrieve("几天无理由", RetrieveConfig.builder().build()).block();

        assertNotNull(docs);
        assertEquals(1, docs.size());
        assertEquals("七天无理由从签收次日算起", docs.get(0).getMetadata().getContentText());
        assertEquals(0.92d, docs.get(0).getScore(), 1e-6);
        assertEquals("售后FAQ", docs.get(0).getMetadata().getPayloadValue("knowledgeBase"),
            "来源信息要随文档带出，供回答里的引用标注使用");
    }

    /**
     * 维度不一致必须跳过整个版本。
     *
     * <p>查询向量由当前 embedding 模型产生，分片向量是投影当时那个模型产生的。
     * 维度不同不是"不太像"，而是分数毫无意义——换 embedding 模型时新旧并存正是这种情况。</p>
     */
    @Test
    @DisplayName("版本维度与查询维度不一致时跳过该版本")
    void skipsVersionOnDimensionMismatch() {
        VectorStore store = mock(VectorStore.class);
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeVersionMapper versionMapper = mock(KnowledgeVersionMapper.class);

        when(versionMapper.selectList(any())).thenReturn(List.of(version(1L, 1024)));

        List<Document> docs = new ManagedKnowledge(store, chunkMapper, versionMapper,
            embedding(4), List.of()).retrieve("问题", RetrieveConfig.builder().build()).block();

        assertNotNull(docs);
        assertTrue(docs.isEmpty());
        verify(store, never()).search(any());
    }

    /** 没有公开分区时不发检索——终端用户不该看到非公开文档。 */
    @Test
    @DisplayName("没有 PUBLIC 分区时不检索")
    void noPublicPartitionsSkipsSearch() {
        VectorStore store = mock(VectorStore.class);
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeVersionMapper versionMapper = mock(KnowledgeVersionMapper.class);

        when(versionMapper.selectList(any())).thenReturn(List.of(version(1L, 4)));
        when(chunkMapper.selectPublicPartitions(anyLong())).thenReturn(List.of());

        List<Document> docs = new ManagedKnowledge(store, chunkMapper, versionMapper,
            embedding(4), List.of()).retrieve("问题", RetrieveConfig.builder().build()).block();

        assertTrue(docs != null && docs.isEmpty());
        verify(store, never()).search(any());
    }

    /** 版本阈值与调用方阈值取较严格的一个：两者都是"不要低质量召回"的表达。 */
    @Test
    @DisplayName("阈值取版本与调用方中较严格的一个")
    void usesStricterThreshold() {
        VectorStore store = mock(VectorStore.class);
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeVersionMapper versionMapper = mock(KnowledgeVersionMapper.class);

        KnowledgeVersionDO v = version(1L, 4);
        v.setScoreThreshold(new BigDecimal("0.30"));
        when(versionMapper.selectList(any())).thenReturn(List.of(v));
        when(chunkMapper.selectPublicPartitions(1L)).thenReturn(List.of(10L));
        AtomicReference<VectorQuery> captured = new AtomicReference<>();
        when(store.search(any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return List.of();
        });

        new ManagedKnowledge(store, chunkMapper, versionMapper, embedding(4), List.of())
            .retrieve("问题", RetrieveConfig.builder().scoreThreshold(0.70d).build()).block();

        assertEquals(0.70d, captured.get().threshold(), 1e-6, "调用方更严格时应取调用方的");
    }

    /**
     * 对话链路不得直接写知识。
     *
     * <p>唯一写入口是后台同步任务经跨库门面投影——那里才有版本、审核与 lineage。
     * 从对话链路塞内容会绕开全部治理，因此显式拒绝而不是静默忽略。</p>
     */
    @Test
    @DisplayName("对话链路写入知识必须被拒绝")
    void addDocumentsIsRejected() {
        ManagedKnowledge knowledge = new ManagedKnowledge(mock(VectorStore.class),
            mock(KnowledgeChunkMapper.class), mock(KnowledgeVersionMapper.class),
            embedding(4), List.of());

        assertThrows(UnsupportedOperationException.class,
            () -> knowledge.addDocuments(List.of()).block());
    }

    /** 检索失败不该让整轮对话崩掉。 */
    @Test
    @DisplayName("检索异常时退化为空结果而不是抛给对话链路")
    void retrieveFailureDegradesToEmpty() {
        VectorStore store = mock(VectorStore.class);
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeVersionMapper versionMapper = mock(KnowledgeVersionMapper.class);
        when(versionMapper.selectList(any())).thenThrow(new IllegalStateException("db down"));

        List<Document> docs = new ManagedKnowledge(store, chunkMapper, versionMapper,
            embedding(4), List.of()).retrieve("问题", RetrieveConfig.builder().build()).block();

        assertTrue(docs != null && docs.isEmpty());
    }

    @Test
    @DisplayName("空查询直接返回空")
    void blankQueryReturnsEmpty() {
        ManagedKnowledge knowledge = new ManagedKnowledge(mock(VectorStore.class),
            mock(KnowledgeChunkMapper.class), mock(KnowledgeVersionMapper.class),
            embedding(4), List.of());

        assertTrue(knowledge.retrieve("  ", RetrieveConfig.builder().build()).block().isEmpty());
    }
}
