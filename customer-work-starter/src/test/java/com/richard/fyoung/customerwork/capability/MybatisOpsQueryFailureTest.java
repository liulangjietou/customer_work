package com.richard.fyoung.customerwork.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.richard.fyoung.customerwork.capability.csat.MybatisCsatStore;
import com.richard.fyoung.customerwork.capability.csat.mapper.CsatSurveyMapper;
import com.richard.fyoung.customerwork.capability.semanticcache.MybatisSemanticCacheStore;
import com.richard.fyoung.customerwork.capability.semanticcache.mapper.SemanticCacheMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 运营查询需要区分“没有记录”与读取失败，不能复用在线缓存命中的降级语义。 */
class MybatisOpsQueryFailureTest {
    private static final String SCOPE = "ops-acceptance";
    private static final int LIMIT = 20;

    @Test
    void csatWindowFailureMustRemainObservable() {
        var mapper = mock(CsatSurveyMapper.class);
        var failure = new IllegalStateException("acceptance read failure");
        when(mapper.selectByWindow(SCOPE, 0L, 100L)).thenThrow(failure);
        var result = assertThrows(RuntimeException.class,
            () -> new MybatisCsatStore(mapper).findByWindow(SCOPE, 0L, 100L));
        assertSame(failure, result.getCause());
    }

    @Test
    void cacheListFailureMustRemainObservable() {
        var mapper = mock(SemanticCacheMapper.class);
        var failure = new IllegalStateException("acceptance read failure");
        when(mapper.selectByHits(SCOPE, LIMIT)).thenThrow(failure);
        var result = assertThrows(RuntimeException.class,
            () -> new MybatisSemanticCacheStore(mapper).listByHits(SCOPE, LIMIT));
        assertSame(failure, result.getCause());
    }

    @Test
    void cacheScopesFailureMustRemainObservable() {
        var mapper = mock(SemanticCacheMapper.class);
        var failure = new IllegalStateException("acceptance read failure");
        when(mapper.selectScopes(LIMIT)).thenThrow(failure);
        var result = assertThrows(RuntimeException.class,
            () -> new MybatisSemanticCacheStore(mapper).listScopes(LIMIT));
        assertSame(failure, result.getCause());
    }

    @Test
    void genuinelyEmptyWindowsAndScopesRemainSuccessful() {
        var csatMapper = mock(CsatSurveyMapper.class);
        when(csatMapper.selectByWindow(SCOPE, 0L, 100L)).thenReturn(List.of());
        assertEquals(List.of(), new MybatisCsatStore(csatMapper).findByWindow(SCOPE, 0L, 100L));
        var cacheMapper = mock(SemanticCacheMapper.class);
        when(cacheMapper.selectByHits(SCOPE, LIMIT)).thenReturn(List.of());
        when(cacheMapper.selectScopes(LIMIT)).thenReturn(List.of());
        var cache = new MybatisSemanticCacheStore(cacheMapper);
        assertEquals(List.of(), cache.listByHits(SCOPE, LIMIT));
        assertEquals(List.of(), cache.listScopes(LIMIT));
    }
}
