package com.richard.fyoung.customerwork.core.middleware;

import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租户上下文中间件单测（onSystemPrompt 第五段）：把租户/会话注入系统提示词。
 * @author owlzhangfq@gmail.com
 */
class TenantContextMiddlewareTest {

    @Test
    void onSystemPrompt_shouldAppendTenantAndSession() {
        TenantContextMiddleware mw = new TenantContextMiddleware();
        RuntimeContext ctx = RuntimeContext.builder().userId("tenantA").sessionId("conv-1").build();

        String prompt = mw.onSystemPrompt(null, ctx, "你是客服助手。").block();

        assertTrue(prompt.contains("你是客服助手。"), "应保留原提示词");
        assertTrue(prompt.contains("tenantA"), "应注入租户");
        assertTrue(prompt.contains("conv-1"), "应注入会话");
    }

    @Test
    void onSystemPrompt_shouldReturnOriginal_whenNoContext() {
        TenantContextMiddleware mw = new TenantContextMiddleware();
        String prompt = mw.onSystemPrompt(null, RuntimeContext.builder().build(), "原文").block();
        assertTrue(prompt.equals("原文"), "无租户/会话时应原样返回");
    }
}
