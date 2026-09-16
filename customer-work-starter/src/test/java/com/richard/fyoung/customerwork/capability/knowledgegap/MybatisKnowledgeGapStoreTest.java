package com.richard.fyoung.customerwork.capability.knowledgegap;

import com.richard.fyoung.customerwork.capability.knowledgegap.mapper.KnowledgeGapMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 写入是旁路统计，读取是运营事实；两者不能共用静默失败语义。 */
class MybatisKnowledgeGapStoreTest {

    private final KnowledgeGapMapper mapper = mock(KnowledgeGapMapper.class);
    private final MybatisKnowledgeGapStore store = new MybatisKnowledgeGapStore(mapper);

    @Test
    void topReadFailureMustNotLookLikeAnEmptyBoard() {
        var failure = new IllegalStateException("database unavailable");
        when(mapper.selectTopGaps("tenant-a", 50)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> store.topGaps("tenant-a", 50)));
    }

    @Test
    void sourceReadFailureMustNotLookLikeNoSignals() {
        var failure = new IllegalStateException("database unavailable");
        when(mapper.selectByScope("tenant-a")).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> store.findAll("tenant-a")));
    }

    @Test
    void recordingFailureStillMustNotInterruptCustomerReply() {
        doThrow(new IllegalStateException("database unavailable")).when(mapper).upsertMiss(any());

        assertDoesNotThrow(() -> store.recordMiss("怎样申请发票", "tenant-a", 1L));
    }
}
