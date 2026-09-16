package com.richard.fyoung.customerwork.capability.knowledgegap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.richard.fyoung.customerwork.capability.knowledgegap.entity.KnowledgeGapDO;
import com.richard.fyoung.customerwork.capability.knowledgegap.mapper.KnowledgeGapMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

/** 规则只处理完整明确的问题，避免把含业务内容的句子按关键词误分流。 */
class KnowledgeGapClassificationTest {
    @Test
    void truncatedGreetingPrefixMustNotHideTheBusinessQuestion() {
        String question = "HELLO" + " ".repeat(500) + "退款流程出错了";
        assertEquals(KnowledgeGapCategory.PENDING,
            KnowledgeGap.firstMiss(question, "tenant-a", 1).classification().category());
        assertEquals(KnowledgeGapCategory.PENDING,
            KnowledgeGapClassification.suggestFromStoredQuestion("HELLO" + " ".repeat(495)).category());
        var memory = new InMemoryKnowledgeGapStore();
        memory.recordMiss(question, "tenant-a", 1);
        assertEquals(KnowledgeGapCategory.PENDING, memory.topGaps("tenant-a", 1).get(0).classification().category());
        var mapper = mock(KnowledgeGapMapper.class);
        new MybatisKnowledgeGapStore(mapper).recordMiss(question, "tenant-a", 1);
        var row = ArgumentCaptor.forClass(KnowledgeGapDO.class);
        verify(mapper).upsertMiss(row.capture());
        assertEquals("PENDING", row.getValue().getCategory());
    }

    @ParameterizedTest
    @CsvSource({
        "今天星期几？, REALTIME", "  现在 几点  , REALTIME", "HELLO!, NON_BUSINESS",
        "早上好呀, NON_BUSINESS", "今天几号可以退款, PENDING", "你好！我要退货, PENDING",
        "现在订单到哪里了, PENDING", "接口报错是否有退款政策, PENDING"
    })
    void ambiguousBusinessQuestionsRemainPending(String question, KnowledgeGapCategory expected) {
        assertEquals(expected, KnowledgeGapClassification.suggest(question).category());
    }
}
