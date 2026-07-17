package com.richard.fyoung.customerwork.dialog;

import com.richard.fyoung.customerwork.middleware.DialogStageMiddleware;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话阶段状态机 + 动态 Prompt 中间件单测（离线确定性，借鉴 AliGo 动态 Prompt 状态机）。
 * @author owlzhangfq@gmail.com
 */
class DialogStageServiceTest {

    private final DialogStageService svc = new DialogStageService();

    @Test
    void current_shouldDefaultToGreeting() {
        assertEquals(DialogStage.GREETING, svc.current("s1"));
        assertEquals(DialogStage.GREETING, svc.current(null));
    }

    @Test
    void setAndAdvance_shouldDriveStateMachine() {
        svc.set("s1", DialogStage.COLLECTING);
        assertEquals(DialogStage.COLLECTING, svc.current("s1"));

        assertEquals(DialogStage.PROCESSING, svc.advance("s1"));
        assertEquals(DialogStage.CONFIRMING, svc.advance("s1"));
        // CONFIRMING 为终态，自保持
        assertEquals(DialogStage.CONFIRMING, svc.advance("s1"));

        svc.reset("s1");
        assertEquals(DialogStage.GREETING, svc.current("s1"));
    }

    @Test
    void everyStage_shouldHaveNonBlankFragment() {
        for (DialogStage stage : DialogStage.values()) {
            assertNotNull(stage.promptFragment());
            assertTrue(stage.promptFragment().length() > 0, "阶段片段不应为空: " + stage);
        }
    }

    @Test
    void middleware_shouldInjectCurrentStageFragment() {
        svc.set("s2", DialogStage.COLLECTING);
        DialogStageMiddleware mw = new DialogStageMiddleware(svc);
        RuntimeContext ctx = RuntimeContext.builder().userId("u").sessionId("s2").build();

        String prompt = mw.onSystemPrompt(null, ctx, "你是客服。").block();

        assertTrue(prompt.startsWith("你是客服。"), "应保留原 prompt");
        assertTrue(prompt.contains("信息收集"), "应注入 COLLECTING 阶段片段: " + prompt);
    }
}
