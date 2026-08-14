package com.richard.fyoung.customerwork.capability.badcase;

import com.richard.fyoung.customerwork.capability.eval.EvalCase;
import com.richard.fyoung.customerwork.capability.eval.EvalCaseSource;
import com.richard.fyoung.customerwork.capability.eval.EvalCaseStore;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import com.richard.fyoung.customerwork.capability.eval.InMemoryEvalCaseStore;
import com.richard.fyoung.customerwork.capability.eval.IntentEvalRunner;
import com.richard.fyoung.customerwork.capability.eval.PersistedEvalCase;
import com.richard.fyoung.customerwork.core.agent.MultiAgentOrchestrator;
import com.richard.fyoung.customerwork.data.chatlog.ChatMessage;
import com.richard.fyoung.customerwork.data.chatlog.ChatMessageStore;
import com.richard.fyoung.customerwork.data.chatlog.InMemoryChatMessageStore;
import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.MockKnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.MockOrderBackend;
import com.richard.fyoung.customerwork.tool.backend.entity.KnowledgeDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.KnowledgeMapper;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * badcase 回流服务单测。
 *
 * <p>最关键的一条是 {@link #adoptAsEvalCase_shouldGrowTheDataset()}：验证飞轮真的转起来了——
 * 一条 badcase 转成评测用例之后，下一次跑评测时它<b>确实出现在评测集里</b>。
 * 只断言"写进了 Store"是不够的，那只证明存了，不证明用上了。</p>
 * @author owlzhangfq@gmail.com
 */
class BadcaseServiceTest {

    private BadcaseStore badcaseStore;
    private EvalCaseStore evalCaseStore;
    private ChatMessageStore chatStore;
    private KnowledgeMapper knowledgeMapper;
    private BadcaseService service;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> absentProvider() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @BeforeEach
    void setUp() {
        badcaseStore = new InMemoryBadcaseStore();
        evalCaseStore = new InMemoryEvalCaseStore();
        chatStore = new InMemoryChatMessageStore();
        knowledgeMapper = mock(KnowledgeMapper.class);
        // insert 时回填自增主键，模拟 MyBatis-Plus 的 useGeneratedKeys 行为
        when(knowledgeMapper.insert(any(KnowledgeDO.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, KnowledgeDO.class).setId(99L);
            return 1;
        });
        service = new BadcaseService(badcaseStore, evalCaseStore, chatStore, knowledgeMapper);
    }

    /** 造一轮一问一答的聊天留痕。 */
    private String seedDialog(String sessionId, String question, String answer) {
        chatStore.append(ChatMessage.of("MSG-Q", sessionId, null, TicketActorType.USER, "u1", question));
        chatStore.append(ChatMessage.of("MSG-A", sessionId, null, TicketActorType.BOT, "bot", answer));
        return "MSG-A";
    }

    @Test
    void record_shouldBackfillDialogContext() {
        String messageId = seedDialog("sess-1", "怎么退货", "请联系客服");

        Badcase badcase = service.record(BadcaseSource.NEGATIVE_FEEDBACK, "sess-1", messageId, "答非所问")
            .orElseThrow();

        // 只给一个 messageId，运营在筛选界面上根本无从判断该不该回流
        assertEquals("怎么退货", badcase.getUserInput());
        assertEquals("请联系客服", badcase.getAgentReply());
        assertTrue(badcase.isPending());
    }

    @Test
    void record_withoutMessageId_shouldFallBackToLastBotReply() {
        seedDialog("sess-1", "运费怎么算", "满99包邮");

        // 质检针对的是一批回复、没有单条 messageId，此时退化为取最后一条机器人回复
        Badcase badcase = service.record(BadcaseSource.QUALITY_FAILURE, "sess-1", null, "score=60")
            .orElseThrow();

        assertEquals("满99包邮", badcase.getAgentReply());
        assertEquals("运费怎么算", badcase.getUserInput());
    }

    @Test
    void record_withoutChatLog_shouldStillEnqueue() {
        BadcaseService noChatLog = new BadcaseService(badcaseStore, evalCaseStore, null, null);

        Badcase badcase = noChatLog.record(BadcaseSource.NEGATIVE_FEEDBACK, "sess-1", "MSG-1", "差评")
            .orElseThrow();

        // 聊天留痕是可选能力，没开时 badcase 仍该被记下来，只是缺上下文
        assertTrue(badcase.isPending());
        assertEquals("差评", badcase.getDetail());
    }

    @Test
    void adoptAsEvalCase_shouldGrowTheDataset() {
        String messageId = seedDialog("sess-1", "我要退款不然投诉", "好的");
        Badcase badcase = service.record(BadcaseSource.NEGATIVE_FEEDBACK, "sess-1", messageId, "答非所问")
            .orElseThrow();

        service.adoptAsEvalCase(badcase.getId(), "bc-refund-1", EvalType.INTENT, null, "模糊-多意图", "alice");

        // 断言飞轮真的转起来了：新用例出现在下一次评测的评测集里，而不只是躺在表里
        IntentEvalRunner runner = new IntentEvalRunner(orchestrator(), evalCaseStore);
        List<EvalCase> dataset = runner.loadDataset();
        assertTrue(dataset.stream().anyMatch(c -> "bc-refund-1".equals(c.id())),
            "回流的用例必须真的进入评测集，否则飞轮只是看起来在转");
        assertEquals("我要退款不然投诉",
            dataset.stream().filter(c -> "bc-refund-1".equals(c.id())).findFirst().orElseThrow().input());

        PersistedEvalCase stored = evalCaseStore.find(EvalType.INTENT, "bc-refund-1").orElseThrow();
        assertEquals(EvalCaseSource.BADCASE, stored.source());
        assertEquals(badcase.getId(), stored.originRef(), "要能溯源回原始会话");
    }

    @Test
    void adoptAsEvalCase_withDuplicateId_shouldFailFast() {
        String messageId = seedDialog("sess-1", "怎么退货", "请联系客服");
        Badcase first = service.record(BadcaseSource.NEGATIVE_FEEDBACK, "sess-1", messageId, "x").orElseThrow();
        Badcase second = service.record(BadcaseSource.NEGATIVE_FEEDBACK, "sess-1", messageId, "y").orElseThrow();
        service.adoptAsEvalCase(first.getId(), "dup-1", EvalType.INTENT, "refund", "退款", "alice");

        // 编号冲突会静默覆盖掉已有用例（upsert 语义），必须提前拦
        assertThrows(IllegalStateException.class,
            () -> service.adoptAsEvalCase(second.getId(), "dup-1", EvalType.INTENT, "refund", "退款", "bob"));
    }

    @Test
    void adoptAsEvalCase_withoutUserInput_shouldFailFast() {
        BadcaseService noChatLog = new BadcaseService(badcaseStore, evalCaseStore, null, null);
        Badcase badcase = noChatLog.record(BadcaseSource.NEGATIVE_FEEDBACK, "sess-1", "MSG-1", "差评")
            .orElseThrow();

        // 没有用户输入就没有评测用例可言——一条没有 input 的用例跑起来毫无意义
        assertThrows(IllegalStateException.class,
            () -> noChatLog.adoptAsEvalCase(badcase.getId(), "c1", EvalType.INTENT, null, "x", "alice"));
    }

    @Test
    void adoptAsKnowledge_shouldWriteEntryAndTrackId() {
        String messageId = seedDialog("sess-1", "开发票怎么开", "不清楚");
        Badcase badcase = service.record(BadcaseSource.NEGATIVE_FEEDBACK, "sess-1", messageId, "没答上来")
            .orElseThrow();

        Badcase adopted = service.adoptAsKnowledge(badcase.getId(), "发票开具规则",
            "订单详情页自助申请，1-3 个工作日开具。", "发票,开票", "alice");

        assertEquals(99L, adopted.getAdoptedKnowledgeId());
        assertEquals(BadcaseStatus.RESOLVED, adopted.getStatus());
        assertEquals(BadcaseStatus.RESOLVED, badcaseStore.find(badcase.getId()).orElseThrow().getStatus(),
            "状态流转必须回写，否则同一条会被反复翻出来");
    }

    @Test
    void adoptAsKnowledge_withoutJdbcBackend_shouldFailFast() {
        BadcaseService noKnowledge = new BadcaseService(badcaseStore, evalCaseStore, chatStore, null);
        Badcase badcase = noKnowledge.record(BadcaseSource.NEGATIVE_FEEDBACK, "sess-1", null, "x")
            .orElseThrow();

        assertThrows(IllegalStateException.class,
            () -> noKnowledge.adoptAsKnowledge(badcase.getId(), "t", "c", "k", "alice"));
    }

    @Test
    void query_shouldFilterByStatusAndSource() {
        seedDialog("sess-1", "q", "a");
        Badcase first = service.record(BadcaseSource.NEGATIVE_FEEDBACK, "sess-1", null, "x").orElseThrow();
        service.record(BadcaseSource.QUALITY_FAILURE, "sess-1", null, "y").orElseThrow();
        service.ignore(first.getId(), "误触", "alice");

        assertEquals(1, service.query(BadcaseQuery.pending(0, 10)).size());
        assertEquals(1, service.count(BadcaseStatus.PENDING, null));
        assertEquals(1, service.count(null, BadcaseSource.NEGATIVE_FEEDBACK));
        assertEquals(2, service.count(null, null));
    }

    @Test
    void find_unknownBadcase_shouldBeEmpty() {
        assertEquals(Optional.empty(), service.find("not-exists"));
    }

    private MultiAgentOrchestrator orchestrator() {
        return new MultiAgentOrchestrator(mock(Model.class), new CustomerWorkProperties(),
            new MockOrderBackend(), new MockAfterSalesBackend(), new MockKnowledgeBackend());
    }
}
