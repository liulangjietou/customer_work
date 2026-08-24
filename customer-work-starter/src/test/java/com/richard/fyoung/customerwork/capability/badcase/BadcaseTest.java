package com.richard.fyoung.customerwork.capability.badcase;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * badcase 状态流转单测。
 *
 * <p>重点是"补知识"与"加评测用例"两件事互不排斥——它们解决的是不同问题：
 * 前者让下次能答对，后者让下次答错能立刻被发现。做成互斥状态会逼运营二选一。</p>
 * @author owlzhangfq@gmail.com
 */
class BadcaseTest {

    private Badcase newBadcase() {
        return new Badcase("bc-1", BadcaseSource.NEGATIVE_FEEDBACK, "sess-1", "MSG-1",
            "怎么退货", "您可以联系客服", "答非所问", 1000L);
    }

    @Test
    void newBadcase_shouldStartPending() {
        Badcase badcase = newBadcase();

        assertEquals(BadcaseStatus.PENDING, badcase.getStatus());
        assertTrue(badcase.isPending());
        assertNull(badcase.getAdoptedKnowledgeId());
        assertNull(badcase.getAdoptedEvalCaseId());
    }

    @Test
    void signalHash_shouldMatchMysqlTrimAndUnicodeCharacterBoundary() {
        String prefix = "😀".repeat(499);
        Badcase normalized = new Badcase("bc-2", BadcaseSource.NEGATIVE_FEEDBACK, "sess-1", "MSG-2",
            "  " + prefix + "问后缀  ", "reply", "detail", 1000L);
        Badcase sameFirstFiveHundredCharacters = new Badcase("bc-3", BadcaseSource.NEGATIVE_FEEDBACK,
            "sess-1", "MSG-3", prefix + "问另一个后缀", "reply", "detail", 1000L);

        assertEquals(sameFirstFiveHundredCharacters.getSignalHash(), normalized.getSignalHash(),
            "Java 新写入必须与 V21 的 LEFT(TRIM(user_input), 500) 使用同一字符边界");
    }

    @Test
    void signalHash_shouldBeAbsentWhenUserInputIsBlank() {
        Badcase badcase = new Badcase("bc-2", BadcaseSource.QUALITY_FAILURE, "sess-1", null,
            "   ", null, "detail", 1000L);

        assertNull(badcase.getSignalHash());
    }

    @Test
    void adoptAsKnowledge_shouldResolve() {
        Badcase badcase = newBadcase();

        badcase.adoptAsKnowledge(42L, "alice", 2000L);

        assertEquals(BadcaseStatus.RESOLVED, badcase.getStatus());
        assertEquals(42L, badcase.getAdoptedKnowledgeId());
        assertEquals("alice", badcase.getHandledBy());
        assertEquals(2000L, badcase.getHandledAtMs());
    }

    @Test
    void adoptAsKnowledgeTwice_shouldFailFast() {
        Badcase badcase = newBadcase();
        badcase.adoptAsKnowledge(42L, "alice", 2000L);

        // 重复采纳只会让知识库多一条重复内容，而重复内容会挤占本就有限的召回位
        assertThrows(IllegalStateException.class, () -> badcase.adoptAsKnowledge(43L, "bob", 3000L));
    }

    @Test
    void adoptAsBothKnowledgeAndEvalCase_shouldBeAllowed() {
        Badcase badcase = newBadcase();

        badcase.adoptAsKnowledge(42L, "alice", 2000L);
        badcase.adoptAsEvalCase("case-1", "alice", 2100L);

        assertEquals(42L, badcase.getAdoptedKnowledgeId());
        assertEquals("case-1", badcase.getAdoptedEvalCaseId());
        assertEquals(BadcaseStatus.RESOLVED, badcase.getStatus());
    }

    @Test
    void adoptAsEvalCaseTwice_shouldFailFast() {
        Badcase badcase = newBadcase();
        badcase.adoptAsEvalCase("case-1", "alice", 2000L);

        assertThrows(IllegalStateException.class, () -> badcase.adoptAsEvalCase("case-2", "bob", 3000L));
    }

    @Test
    void ignore_shouldMarkIgnoredWithReason() {
        Badcase badcase = newBadcase();

        badcase.ignore("alice", "用户误触", 2000L);

        assertEquals(BadcaseStatus.IGNORED, badcase.getStatus());
        assertEquals("用户误触", badcase.getIgnoreReason());
    }

    @Test
    void ignoreAfterResolved_shouldFailFast() {
        Badcase badcase = newBadcase();
        badcase.adoptAsKnowledge(42L, "alice", 2000L);

        // 已经补进知识库的内容不该在记录上显示为"不值得处理"，复盘时会对不上账
        assertThrows(IllegalStateException.class, () -> badcase.ignore("bob", "算了", 3000L));
    }

    @Test
    void adoptAfterIgnored_shouldReopen() {
        Badcase badcase = newBadcase();
        badcase.ignore("alice", "先放着", 2000L);

        badcase.adoptAsEvalCase("case-1", "bob", 3000L);

        assertEquals(BadcaseStatus.RESOLVED, badcase.getStatus(), "运营改主意是合理的");
        assertNull(badcase.getIgnoreReason(), "转正后忽略原因应清掉，否则界面上会自相矛盾");
    }
}
