package com.richard.fyoung.customeradmin.workspace.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.config.AdminSandboxProperties;
import com.richard.fyoung.customeradmin.config.AdminSandboxProperties.SandboxPermissionMode;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatNodeKind;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.PlanConfirmationService;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.PlanConfirmationService.PlanChannel;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link PlanConfirmationMiddleware} 单测：bypass 透传、无通道兜底、hitl 批准原样执行、hitl 拒绝改写取消。
 * @author owlzhangfq@gmail.com
 */
class PlanConfirmationMiddlewareTest {

    private static final String RISKY_COMMAND = "rm -rf sessions/x";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private AdminSandboxProperties props(SandboxPermissionMode mode) {
        AdminSandboxProperties p = new AdminSandboxProperties();
        p.setPermissionMode(mode);
        p.getHitl().setConfirmTimeoutSeconds(10);
        return p;
    }

    private ActingInput riskyInput() {
        return new ActingInput(List.of(new ToolUseBlock("id-1", "shell_execute", Map.of("command", RISKY_COMMAND))));
    }

    private RuntimeContext ctx() {
        return RuntimeContext.builder().userId("coder").sessionId("s1").build();
    }

    /** 订阅通道事件，收到 plan 时把 planId 投进队列，供测试线程驱动确认。 */
    private LinkedBlockingQueue<String> capturePlanIds(PlanConfirmationService svc, PlanChannel channel) {
        LinkedBlockingQueue<String> planIds = new LinkedBlockingQueue<>();
        svc.events(channel).subscribe(chunk -> {
            if (chunk.kind() == ChatNodeKind.PLAN) {
                try {
                    JsonNode node = objectMapper.readTree(chunk.text());
                    planIds.offer(node.get("planId").asText());
                } catch (Exception ignored) {
                    // 测试辅助，解析失败忽略
                }
            }
        });
        return planIds;
    }

    @Test
    void onActing_shouldPassThrough_whenBypassMode() {
        AdminSandboxProperties props = props(SandboxPermissionMode.BYPASS);
        PlanConfirmationService svc = new PlanConfirmationService();
        PlanConfirmationMiddleware mw = new PlanConfirmationMiddleware(props, new SandboxRiskDetector(props), svc);
        ActingInput input = riskyInput();

        AtomicReference<ActingInput> received = new AtomicReference<>();
        mw.onActing(null, ctx(), input, in -> {
            received.set(in);
            return Flux.empty();
        }).blockLast(Duration.ofSeconds(5));

        assertSame(input, received.get(), "bypass 模式应原样透传，不挂起");
    }

    @Test
    void onActing_shouldPassThrough_whenHitlButNoChannel() {
        AdminSandboxProperties props = props(SandboxPermissionMode.HITL);
        PlanConfirmationService svc = new PlanConfirmationService();
        PlanConfirmationMiddleware mw = new PlanConfirmationMiddleware(props, new SandboxRiskDetector(props), svc);
        ActingInput input = riskyInput();

        AtomicReference<ActingInput> received = new AtomicReference<>();
        // 未 openChannel：无 SSE 承接确认 → fast fail 放行给护栏兜底
        mw.onActing(null, ctx(), input, in -> {
            received.set(in);
            return Flux.empty();
        }).blockLast(Duration.ofSeconds(5));

        assertSame(input, received.get(), "hitl 但无通道时应放行原始调用交给护栏兜底");
    }

    @Test
    void onActing_shouldExecuteOriginal_whenApproved() throws Exception {
        AdminSandboxProperties props = props(SandboxPermissionMode.HITL);
        PlanConfirmationService svc = new PlanConfirmationService();
        PlanConfirmationMiddleware mw = new PlanConfirmationMiddleware(props, new SandboxRiskDetector(props), svc);
        PlanChannel channel = svc.openChannel("coder", "s1");
        LinkedBlockingQueue<String> planIds = capturePlanIds(svc, channel);

        // 独立线程：拿到 planId 后批准
        Thread confirmer = new Thread(() -> {
            try {
                String planId = planIds.poll(5, TimeUnit.SECONDS);
                svc.confirm("coder", "s1", planId, true);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        confirmer.start();

        ActingInput input = riskyInput();
        AtomicReference<ActingInput> received = new AtomicReference<>();
        mw.onActing(null, ctx(), input, in -> {
            received.set(in);
            return Flux.empty();
        }).blockLast(Duration.ofSeconds(10));
        confirmer.join(2000);

        assertEquals(RISKY_COMMAND, received.get().toolCalls().get(0).getInput().get("command"),
            "批准后应原样执行原始命令");
    }

    @Test
    void onActing_shouldNeutralize_whenRejected() throws Exception {
        AdminSandboxProperties props = props(SandboxPermissionMode.HITL);
        PlanConfirmationService svc = new PlanConfirmationService();
        PlanConfirmationMiddleware mw = new PlanConfirmationMiddleware(props, new SandboxRiskDetector(props), svc);
        PlanChannel channel = svc.openChannel("coder", "s1");
        LinkedBlockingQueue<String> planIds = capturePlanIds(svc, channel);

        Thread confirmer = new Thread(() -> {
            try {
                String planId = planIds.poll(5, TimeUnit.SECONDS);
                svc.confirm("coder", "s1", planId, false);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        confirmer.start();

        ActingInput input = riskyInput();
        AtomicReference<ActingInput> received = new AtomicReference<>();
        mw.onActing(null, ctx(), input, in -> {
            received.set(in);
            return Flux.empty();
        }).blockLast(Duration.ofSeconds(10));
        confirmer.join(2000);

        Object command = received.get().toolCalls().get(0).getInput().get("command");
        assertEquals("[PLAN_REJECTED_BY_USER]", command, "拒绝后风险入参应被改写为拒绝占位（取消该操作）");
    }

    /**
     * BATCH_MODIFY 拒绝闭环回归：纯批量普通写入（每个调用单独 assess 均为空、isHighRisk=false）触发
     * 整步 BATCH_MODIFY 挂起，拒绝后<b>全部写调用</b>必须被整体改写取消——只按 isHighRisk 精确改写
     * 会导致所有写入原样执行（批量拒绝失效）。
     */
    @Test
    void onActing_shouldNeutralizeAllWriteTools_whenBatchModifyRejected() throws Exception {
        AdminSandboxProperties props = props(SandboxPermissionMode.HITL);
        PlanConfirmationService svc = new PlanConfirmationService();
        PlanConfirmationMiddleware mw = new PlanConfirmationMiddleware(props, new SandboxRiskDetector(props), svc);
        PlanChannel channel = svc.openChannel("coder", "s1");
        LinkedBlockingQueue<String> planIds = capturePlanIds(svc, channel);

        Thread confirmer = new Thread(() -> {
            try {
                String planId = planIds.poll(5, TimeUnit.SECONDS);
                svc.confirm("coder", "s1", planId, false);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        confirmer.start();

        // 4 个普通写入（无任何单工具风险）> 阈值 3 → 仅 BATCH_MODIFY 聚合风险
        ActingInput input = new ActingInput(List.of(
            new ToolUseBlock("id-1", "write_file", Map.of("path", "sessions/x/a.java", "content", "class A {}")),
            new ToolUseBlock("id-2", "write_file", Map.of("path", "sessions/x/b.java", "content", "class B {}")),
            new ToolUseBlock("id-3", "edit_file", Map.of("path", "sessions/x/c.java", "content", "class C {}")),
            new ToolUseBlock("id-4", "create_file", Map.of("path", "sessions/x/d.java", "content", "class D {}"))));
        AtomicReference<ActingInput> received = new AtomicReference<>();
        mw.onActing(null, ctx(), input, in -> {
            received.set(in);
            return Flux.empty();
        }).blockLast(Duration.ofSeconds(10));
        confirmer.join(2000);

        for (ToolUseBlock use : received.get().toolCalls()) {
            assertEquals("[PLAN_REJECTED_BY_USER]", use.getInput().get("path"),
                "批量拒绝后每个写调用的 path 都应被改写取消: " + use.getName());
            assertEquals("[PLAN_REJECTED_BY_USER]", use.getInput().get("content"),
                "批量拒绝后每个写调用的 content 都应被改写取消: " + use.getName());
        }
    }
}
