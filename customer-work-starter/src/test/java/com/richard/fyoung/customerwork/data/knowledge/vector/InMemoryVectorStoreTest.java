package com.richard.fyoung.customerwork.data.knowledge.vector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内存向量存储测试。
 *
 * @author owlzhangfq@gmail.com
 */
class InMemoryVectorStoreTest {

    private static final String NS = "kb-1";

    private InMemoryVectorStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryVectorStore();
        store.upsert(NS, "c1", "doc-1", new float[]{1f, 0f, 0f});
        store.upsert(NS, "c2", "doc-1", new float[]{0.9f, 0.1f, 0f});
        store.upsert(NS, "c3", "doc-2", new float[]{0f, 1f, 0f});
    }

    @Test
    @DisplayName("按相似度排序返回")
    void returnsSortedByScore() {
        List<VectorMatch> matches = store.search(
            new VectorQuery(NS, List.of("doc-1", "doc-2"), new float[]{1f, 0f, 0f}, 3, 0d));

        assertEquals(3, matches.size());
        assertEquals("c1", matches.get(0).chunkId(), "完全一致的应排第一");
        assertTrue(matches.get(0).score() >= matches.get(1).score());
    }

    /**
     * 权限过滤后无可查分区时必须返回空。
     *
     * <p>这是隔离底线：语义是"查不到"，落成"全量检索"就是越权——
     * 用户会拿到他本来看不到的知识内容，而且不报任何错。</p>
     */
    @Test
    @DisplayName("分区集合为空必须返回空，不得退化成全量检索")
    void emptyPartitionsReturnsEmpty() {
        List<VectorMatch> matches = store.search(
            new VectorQuery(NS, List.of(), new float[]{1f, 0f, 0f}, 3, 0d));

        assertTrue(matches.isEmpty(), "无可查分区时返回全量就是越权");
    }

    @Test
    @DisplayName("只在指定分区内检索")
    void filtersByPartition() {
        List<VectorMatch> matches = store.search(
            new VectorQuery(NS, List.of("doc-2"), new float[]{1f, 0f, 0f}, 10, -1d));

        assertEquals(1, matches.size());
        assertEquals("c3", matches.get(0).chunkId());
    }

    @Test
    @DisplayName("低于阈值的命中被丢弃")
    void dropsBelowThreshold() {
        List<VectorMatch> matches = store.search(
            new VectorQuery(NS, List.of("doc-1", "doc-2"), new float[]{1f, 0f, 0f}, 10, 0.5d));

        assertEquals(2, matches.size(), "正交向量的余弦为 0，应被 0.5 的阈值挡掉");
    }

    @Test
    @DisplayName("topN 截断")
    void limitsToTopN() {
        List<VectorMatch> matches = store.search(
            new VectorQuery(NS, List.of("doc-1", "doc-2"), new float[]{1f, 0f, 0f}, 1, -1d));

        assertEquals(1, matches.size());
        assertEquals("c1", matches.get(0).chunkId());
    }

    @Test
    @DisplayName("未知命名空间返回空")
    void unknownNamespaceReturnsEmpty() {
        assertTrue(store.search(
            new VectorQuery("nope", List.of("doc-1"), new float[]{1f, 0f, 0f}, 3, 0d)).isEmpty());
    }
}
