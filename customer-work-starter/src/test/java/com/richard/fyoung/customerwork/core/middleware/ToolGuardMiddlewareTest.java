package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.richard.fyoung.customerwork.infra.config.properties.HooksProperties;

/**
 * 工具护栏中间件单测（onActing 入参治理）：公共参数注入 + 数值上限钳制。
 * @author owlzhangfq@gmail.com
 */
class ToolGuardMiddlewareTest {

    private CustomerWorkProperties propsWith(Map<String, String> inject, Map<String, Double> caps) {
        CustomerWorkProperties props = new CustomerWorkProperties();
        HooksProperties.ToolGuard cfg = props.getHooks().getToolGuard();
        cfg.setEnabled(true);
        cfg.getInjectParams().putAll(inject);
        cfg.getNumericCaps().putAll(caps);
        return props;
    }

    @Test
    void guard_shouldInjectMissingParam_andClampNumericCap() {
        ToolGuardMiddleware mw = new ToolGuardMiddleware(
            propsWith(Map.of("channel", "app"), Map.of("amount", 1000.0)));

        ToolUseBlock use = new ToolUseBlock("t1", "submitRefund", Map.of("amount", 5000.0), null);
        ToolUseBlock guarded = mw.guard(use);

        assertEquals("app", guarded.getInput().get("channel"), "应注入缺失的公共参数");
        assertEquals(1000.0, ((Number) guarded.getInput().get("amount")).doubleValue(),
            "超限金额应被钳制到上限");
    }

    @Test
    void guard_shouldReturnNull_whenNoChange() {
        ToolGuardMiddleware mw = new ToolGuardMiddleware(propsWith(Map.of(), Map.of()));
        assertNull(mw.guard(new ToolUseBlock("t2", "queryOrder", Map.of("orderNo", "X"), null)));
    }

    @Test
    void guard_shouldRewriteDestructiveParam_andCount() {
        // 默认破坏性模式生效（rm -rf 等）
        ToolGuardMiddleware mw = new ToolGuardMiddleware(propsWith(Map.of(), Map.of()));

        ToolUseBlock use = new ToolUseBlock("t3", "shellExec",
            Map.of("command", "rm -rf /data/workspace"), null);
        ToolUseBlock guarded = mw.guard(use);

        assertEquals(HooksProperties.ToolGuard.DESTRUCTIVE_PLACEHOLDER,
            guarded.getInput().get("command"), "命中破坏性模式的入参应被改写为安全占位");
        assertEquals(1L, mw.destructiveHitCount(), "命中应被计数");
    }

    @Test
    void guard_shouldNotTouchBenignParam() {
        ToolGuardMiddleware mw = new ToolGuardMiddleware(propsWith(Map.of(), Map.of()));

        ToolUseBlock use = new ToolUseBlock("t4", "shellExec",
            Map.of("command", "ls -al /data/workspace"), null);
        assertNull(mw.guard(use), "未命中破坏性模式不应改写");
        assertEquals(0L, mw.destructiveHitCount(), "未命中不应计数");
    }

    @Test
    void guard_shouldRespectConfiguredDestructivePatterns() {
        CustomerWorkProperties props = propsWith(Map.of(), Map.of());
        // 覆盖默认模式：只拦 "danger" 这一自定义模式
        props.getHooks().getToolGuard().setDestructivePatterns(List.of("danger"));
        ToolGuardMiddleware mw = new ToolGuardMiddleware(props);

        // 默认会拦的 rm -rf 现在不在自定义列表里，应放行
        assertNull(mw.guard(new ToolUseBlock("t5", "shellExec",
            Map.of("command", "rm -rf /tmp"), null)), "覆盖默认模式后 rm -rf 应放行");
        // 自定义模式命中
        ToolUseBlock guarded = mw.guard(new ToolUseBlock("t6", "shellExec",
            Map.of("command", "run danger op"), null));
        assertEquals(HooksProperties.ToolGuard.DESTRUCTIVE_PLACEHOLDER,
            guarded.getInput().get("command"), "自定义破坏性模式应命中改写");
    }

    @Test
    void onActing_shouldRewriteToolCalls_whenGuarded() {
        ToolGuardMiddleware mw = new ToolGuardMiddleware(
            propsWith(Map.of("channel", "app"), Map.of()));
        AtomicReference<ActingInput> captured = new AtomicReference<>();

        ActingInput in = new ActingInput(List.of(
            new ToolUseBlock("t1", "submitRefund", Map.of("amount", 1.0), null)));
        mw.onActing(null, null, in, input -> {
            captured.set(input);
            return Flux.<AgentEvent>empty();
        }).blockLast();

        assertTrue(captured.get().toolCalls().get(0).getInput().containsKey("channel"),
            "下游应收到注入公共参数后的工具调用");
    }
}
