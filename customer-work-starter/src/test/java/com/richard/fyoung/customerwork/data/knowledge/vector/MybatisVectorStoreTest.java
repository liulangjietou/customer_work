package com.richard.fyoung.customerwork.data.knowledge.vector;

import com.richard.fyoung.customerwork.data.knowledge.entity.ChunkVectorDO;
import com.richard.fyoung.customerwork.data.knowledge.mapper.KnowledgeChunkMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MySQL 向量检索测试。
 *
 * <p><b>守的是什么</b>：这条链路取代的那版实现是「把该版本下全部已授权 chunk 一次性
 * selectList 进内存（连 LONGTEXT 正文一起）→ 逐条 JSON 解析 → Java 里算余弦 → 内存排序」。
 * 今天它只打在后台工作台所以没炸；一旦 C 端知识链路打通，同一段代码就会挂到每个用户的每一轮对话上，
 * 既是首字延迟的主要来源，也是一次知识库扩容就能触发的 OOM 事故点。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class MybatisVectorStoreTest {

    private ChunkVectorDO row(long id, long revision, float... vector) {
        ChunkVectorDO do1 = new ChunkVectorDO();
        do1.setId(id);
        do1.setDocRevisionId(revision);
        do1.setEmbedding(VectorCodec.encode(vector));
        return do1;
    }

    /**
     * 分区为空时<b>一次库都不能查</b>。
     *
     * <p>不只是"返回空"——权限过滤后无可查分区，连查询都不该发出去。</p>
     */
    @Test
    @DisplayName("分区为空时不查库且返回空")
    void emptyPartitionsShortCircuits() {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        MybatisVectorStore store = new MybatisVectorStore(mapper);

        List<VectorMatch> matches = store.search(
            new VectorQuery("1", List.of(), new float[]{1f, 0f}, 3, 0d));

        assertTrue(matches.isEmpty());
        verify(mapper, never()).selectVectorsByPartitions(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("按相似度返回 topN")
    void returnsTopN() {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        when(mapper.selectVectorsByPartitions(anyLong(), any(), anyLong(), anyInt()))
            .thenReturn(List.of(
                row(1L, 10L, 1f, 0f),
                row(2L, 10L, 0.7071f, 0.7071f),
                row(3L, 10L, 0f, 1f)))
            .thenReturn(List.of());
        MybatisVectorStore store = new MybatisVectorStore(mapper, 500);

        List<VectorMatch> matches = store.search(
            new VectorQuery("1", List.of("10"), new float[]{1f, 0f}, 2, -1d));

        assertEquals(2, matches.size(), "应截断到 topN");
        assertEquals("1", matches.get(0).chunkId(), "最相近的排第一");
        assertTrue(matches.get(0).score() > matches.get(1).score(), "必须按分数降序");
    }

    /**
     * 维度不一致必须跳过。
     *
     * <p>算出来的分数没有意义，把它当成一条低分命中会污染排序——
     * 而换 embedding 模型时新旧维度并存正是最容易出现这种数据的时刻。</p>
     */
    @Test
    @DisplayName("维度不一致的分片被跳过而不是算成低分")
    void skipsDimensionMismatch() {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        when(mapper.selectVectorsByPartitions(anyLong(), any(), anyLong(), anyInt()))
            .thenReturn(List.of(
                row(1L, 10L, 1f, 0f, 0f),
                row(2L, 10L, 1f, 0f)))
            .thenReturn(List.of());
        MybatisVectorStore store = new MybatisVectorStore(mapper, 500);

        List<VectorMatch> matches = store.search(
            new VectorQuery("1", List.of("10"), new float[]{1f, 0f}, 10, -1d));

        assertEquals(1, matches.size(), "三维那条与二维查询不可比，应被跳过");
        assertEquals("2", matches.get(0).chunkId());
    }

    /**
     * 分批推进，不一次性全拉。
     *
     * <p>这是替代那版「无 limit 无分页的 selectList」的关键：每批按主键游标推进，
     * 内存里只留 topN 的堆。</p>
     */
    @Test
    @DisplayName("按主键游标分批拉取直到取尽")
    void pagesThroughBatches() {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        List<ChunkVectorDO> full = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            full.add(row(i, 10L, 1f, 0f));
        }
        when(mapper.selectVectorsByPartitions(anyLong(), any(), anyLong(), anyInt()))
            .thenReturn(full)
            .thenReturn(List.of(row(3L, 10L, 1f, 0f)))
            .thenReturn(List.of());
        MybatisVectorStore store = new MybatisVectorStore(mapper, 2);

        List<VectorMatch> matches = store.search(
            new VectorQuery("1", List.of("10"), new float[]{1f, 0f}, 10, -1d));

        assertEquals(3, matches.size(), "跨批的分片都应参与打分");
        // 满批后继续取下一批，直到返回不足一批为止
        verify(mapper, org.mockito.Mockito.times(2))
            .selectVectorsByPartitions(anyLong(), any(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("低于阈值的命中被丢弃")
    void dropsBelowThreshold() {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        when(mapper.selectVectorsByPartitions(anyLong(), any(), anyLong(), anyInt()))
            .thenReturn(List.of(row(1L, 10L, 0f, 1f)))
            .thenReturn(List.of());
        MybatisVectorStore store = new MybatisVectorStore(mapper, 500);

        List<VectorMatch> matches = store.search(
            new VectorQuery("1", List.of("10"), new float[]{1f, 0f}, 10, 0.5d));

        assertTrue(matches.isEmpty(), "正交向量余弦为 0，应被 0.5 阈值挡掉");
    }

    @Test
    @DisplayName("命名空间非法时返回空")
    void illegalNamespaceReturnsEmpty() {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        MybatisVectorStore store = new MybatisVectorStore(mapper);

        assertTrue(store.search(
            new VectorQuery("not-a-number", List.of("10"), new float[]{1f, 0f}, 3, 0d)).isEmpty());
        verify(mapper, never()).selectVectorsByPartitions(any(), any(), any(), anyInt());
    }
}
