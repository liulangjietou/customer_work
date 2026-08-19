package com.richard.fyoung.customerwork.capability.semanticcache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分区列表：给运营看板的分区选择器供数。
 *
 * <p>语义缓存的分区键是<b>用户级</b>隔离键（{@code u42} 这样的），这个粒度是安全底线不能动——
 * 但代价是运营在看板上根本猜不到该填什么，看板因此长期显示"No Data"。
 * 与其让人手填一个猜不到的 ID，不如把实际存在的分区列出来让他选。</p>
 * @author owlzhangfq@gmail.com
 */
class SemanticCacheScopeListTest {

    private SemanticCacheStore store;

    @BeforeEach
    void setUp() {
        store = new InMemorySemanticCacheStore();
    }

    @Test
    void shouldAggregateByScopeAndRankByEntries() {
        save("u1", "怎么退货");
        save("u1", "运费怎么算");
        save("u1", "能开专票吗");
        save("u2", "怎么退货");

        List<SemanticCacheScope> scopes = store.listScopes(10);

        assertEquals(2, scopes.size(), "同一分区聚合成一条，不是逐条流水");
        assertEquals("u1", scopes.get(0).scopeId(), "条目多的排前面——运营多半就是想看它");
        assertEquals(3, scopes.get(0).entries());
        assertEquals(1, scopes.get(1).entries());
    }

    @Test
    void shouldRespectLimit() {
        // 分区数随活跃用户数增长，全量返回会在用户一多时把看板拖垮
        for (int i = 0; i < 20; i++) {
            save("u" + i, "怎么退货");
        }

        assertEquals(5, store.listScopes(5).size());
    }

    @Test
    void emptyStore_shouldReturnEmptyList() {
        assertTrue(store.listScopes(10).isEmpty());
    }

    @Test
    void clearedScope_shouldDisappear() {
        // 清空分区后它会从选择器里消失，前端据此重新挑一个
        save("u1", "怎么退货");
        save("u2", "运费怎么算");

        store.clear("u1");

        List<SemanticCacheScope> scopes = store.listScopes(10);
        assertEquals(1, scopes.size());
        assertEquals("u2", scopes.get(0).scopeId());
    }

    private void save(String scopeId, String question) {
        store.save(SemanticCacheEntry.of(scopeId, "consult", question, "0.1,0.2", "答案",
            System.currentTimeMillis()));
    }
}
