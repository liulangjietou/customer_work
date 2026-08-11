package com.richard.fyoung.customerwork.safety.sensitiveword;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 词表刷新器单测：指纹不变不重建 / 词表变更后重建 / 指纹读失败保留旧词表 / fail-closed 自动恢复。
 * @author owlzhangfq@gmail.com
 */
class SensitiveWordRefresherTest {

    /** 可控 Store：能开关"读失败"，用于验证刷新器的失败分支。 */
    private static final class ControllableStore implements SensitiveWordStore {
        private final InMemorySensitiveWordStore delegate = new InMemorySensitiveWordStore();
        private boolean failing;

        void fail(boolean failing) {
            this.failing = failing;
        }

        @Override
        public List<SensitiveWord> findAll() {
            return delegate.findAll();
        }

        @Override
        public Optional<List<SensitiveWord>> findEnabled() {
            return failing ? Optional.empty() : delegate.findEnabled();
        }

        @Override
        public void save(SensitiveWord word) {
            delegate.save(word);
        }
    }

    @Test
    void refreshOnce_shouldNotRebuild_whenFingerprintUnchanged() {
        ControllableStore store = new ControllableStore();
        SensitiveWordFilter filter = new SensitiveWordFilter(store, '*', SensitiveWordAction.BLOCK);
        SensitiveWordRefresher refresher = new SensitiveWordRefresher(store, filter, true);

        assertFalse(refresher.refreshOnce(), "词表没变时不应重建");
    }

    @Test
    void refreshOnce_shouldRebuild_whenWordAdded() {
        ControllableStore store = new ControllableStore();
        SensitiveWordFilter filter = new SensitiveWordFilter(store, '*', SensitiveWordAction.BLOCK);
        SensitiveWordRefresher refresher = new SensitiveWordRefresher(store, filter, true);
        int before = filter.patternCount();

        store.save(SensitiveWord.of("新增拦截词", SensitiveWordCategory.CUSTOM, SensitiveWordAction.BLOCK));

        assertTrue(refresher.refreshOnce(), "词表变更后应重建");
        assertEquals(before + 1, filter.patternCount());
        assertTrue(filter.check("这里有新增拦截词").blocked(), "新词应立即生效");
    }

    @Test
    void refreshOnce_shouldKeepLastGoodTable_whenProbeFails() {
        ControllableStore store = new ControllableStore();
        SensitiveWordFilter filter = new SensitiveWordFilter(store, '*', SensitiveWordAction.BLOCK);
        SensitiveWordRefresher refresher = new SensitiveWordRefresher(store, filter, true);
        int loaded = filter.patternCount();

        store.fail(true);

        assertFalse(refresher.refreshOnce(), "指纹读失败时本轮不重建");
        assertEquals(loaded, filter.patternCount(), "读失败必须保留上一份好词表");
        assertFalse(filter.isFailClosed(), "已加载过好词表就不该退化成拦截一切");
    }

    @Test
    void refreshOnce_shouldRecoverFromFailClosed_whenStoreComesBack() {
        ControllableStore store = new ControllableStore();
        store.fail(true);
        // 首次加载即失败 → 进入 fail-closed 拦截一切
        SensitiveWordFilter filter = new SensitiveWordFilter(store, '*', SensitiveWordAction.BLOCK);
        assertTrue(filter.isFailClosed(), "首次加载失败应进入 fail-closed");
        SensitiveWordRefresher refresher = new SensitiveWordRefresher(store, filter, true);

        store.fail(false);

        assertTrue(refresher.refreshOnce(), "存储恢复后应重建");
        assertFalse(filter.isFailClosed(), "重建成功应自动脱离 fail-closed，无需重启进程");
    }
}
