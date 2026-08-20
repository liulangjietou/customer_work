package com.richard.fyoung.customerwork.data.rag.search;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ReasoningInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识盲区埋点的<b>中间件路径</b>覆盖测试。
 *
 * <p><b>守的是什么 bug</b>：RAG 有两条互不相通的路径——模型主动调 {@code KnowledgeBaseTools}
 * （工具路径，早有埋点）与本中间件自动注入（后台工作台智能体走这条）。盲区埋点原先只做在
 * 工具路径上，判定依据是 {@code KnowledgeBackend.isMiss} 的文案契约，而中间件路径根本不经过
 * {@code KnowledgeBackend}，于是这半条链路的未命中一条都统计不到，盲区看板长期只反映一半。</p>
 *
 * <p>同时守住一条反向约定：<b>检索故障不算盲区</b>。故障是外部服务的可用性问题，
 * 混进盲区排行会让运营看到一批根本不存在的"用户在问但答不上来的问题"。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class KnowledgeInjectionGapRecordingTest {

    /** 记录被埋点的问题，供断言。 */
    private static final class RecordingGapRecorder implements KnowledgeGapRecorder {
        private final List<String> questions = new ArrayList<>();

        @Override
        public void recordMiss(String sessionId, String question) {
            questions.add(question);
        }
    }

    private ReasoningInput inputWith(String userText) {
        return new ReasoningInput(List.of(Msg.builder().role(MsgRole.USER).name("user")
            .content(TextBlock.builder().text(userText).build()).build()), null, null);
    }

    private Flux<AgentEvent> runOnce(KnowledgeInjectionMiddleware mw, String userText) {
        return mw.onReasoning(null, RuntimeContext.builder().userId("u1").sessionId("s1").build(),
            inputWith(userText), in -> Flux.empty());
    }

    @Test
    @DisplayName("检索正常但无召回 = 记一次盲区")
    void emptyRetrievalRecordsGap() {
        RecordingGapRecorder recorder = new RecordingGapRecorder();
        KnowledgeInjectionMiddleware mw = new KnowledgeInjectionMiddleware(
            (agentCode, query) -> "", "agent-a", recorder);

        runOnce(mw, "你们支持开专票吗").blockLast();

        assertEquals(1, recorder.questions.size(),
            "中间件路径的未命中没有被记录 —— 盲区看板会漏掉后台侧的全部数据");
        assertTrue(recorder.questions.get(0).contains("专票"));
    }

    @Test
    @DisplayName("有召回内容时不记盲区")
    void successfulRetrievalRecordsNothing() {
        RecordingGapRecorder recorder = new RecordingGapRecorder();
        KnowledgeInjectionMiddleware mw = new KnowledgeInjectionMiddleware(
            (agentCode, query) -> "支持开具增值税专用发票", "agent-a", recorder);

        runOnce(mw, "你们支持开专票吗").blockLast();

        assertTrue(recorder.questions.isEmpty(), "命中了却记成盲区");
    }

    /** 检索服务挂了不是知识盲区，是可用性问题，两者绝不能混在一张排行榜上。 */
    @Test
    @DisplayName("检索抛异常时不记盲区")
    void retrievalFailureDoesNotRecordGap() {
        RecordingGapRecorder recorder = new RecordingGapRecorder();
        KnowledgeInjectionMiddleware mw = new KnowledgeInjectionMiddleware(
            (agentCode, query) -> {
                throw new IllegalStateException("rag service down");
            }, "agent-a", recorder);

        runOnce(mw, "你们支持开专票吗").blockLast();

        assertTrue(recorder.questions.isEmpty(),
            "检索故障被计成了知识盲区 —— 运营会看到一批并不存在的'答不上来的问题'");
    }

    /** 埋点是旁路，未装配时整条链路必须照常工作。 */
    @Test
    @DisplayName("未装配埋点器时不影响主链路")
    void worksWithoutRecorder() {
        KnowledgeInjectionMiddleware mw = new KnowledgeInjectionMiddleware(
            (agentCode, query) -> "", "agent-a");

        runOnce(mw, "随便问问").blockLast();
    }
}
