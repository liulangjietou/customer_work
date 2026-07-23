package com.richard.fyoung.customeradmin.workspace.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.config.AdminSandboxProperties;
import com.richard.fyoung.customeradmin.config.AdminSandboxProperties.SandboxPermissionMode;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatNodeKind;
import com.richard.fyoung.customeradmin.workspace.runtime.mode.ExecutionMode;
import com.richard.fyoung.customeradmin.workspace.runtime.mode.ExecutionModePolicy;
import com.richard.fyoung.customeradmin.workspace.runtime.mode.ExecutionModeRegistry;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.PlanConfirmationService;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.PlanConfirmationService.PlanChannel;
import io.agentscope.core.agent.RuntimeContext;
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
 * {@link ExecutionModeMiddleware} 单测：五档模式闸门——全局回落（未指定→BYPASS/AUTO）、显式 BYPASS 透传、
 * AUTO 批准/拒绝/无通道兜底/批量拒绝、MANUAL 逐工具确认+整体取消、ACCEPT_EDITS 编辑放行+删除仍确认、
 * PLAN mutating 拦改+只读放行。
 * @author owlzhangfq@gmail.com
 */
class ExecutionModeMiddlewareTest {

    private static final String RISKY_COMMAND = "rm -rf sessions/x";
    private static final String REJECT_NOTICE = "[PLAN_REJECTED_BY_USER]";
    private static final String PLAN_BLOCK_NOTICE =
        "[PLAN_MODE_BLOCKED] Plan mode: mutating tools are disabled. Present an implementation plan instead of executing.";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private AdminSandboxProperties props(SandboxPermissionMode mode) {
        AdminSandboxProperties p = new AdminSandboxProperties();
        p.setPermissionMode(mode);
        p.getHitl().setConfirmTimeoutSeconds(10);
        return p;
    }

    private ExecutionModeMiddleware middleware(AdminSandboxProperties props, ExecutionModeRegistry registry,
                                               PlanConfirmationService svc) {
        SandboxRiskDetector detector = new SandboxRiskDetector(props);
        ExecutionModePolicy policy = new ExecutionModePolicy(detector);
        return new ExecutionModeMiddleware(props, detector, registry, policy, svc);
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

    private ActingInput drive(ExecutionModeMiddleware mw, ActingInput input) {
        AtomicReference<ActingInput> received = new AtomicReference<>();
        mw.onActing(null, ctx(), input, in -> {
            received.set(in);
            return Flux.empty();
        }).blockLast(Duration.ofSeconds(10));
        return received.get();
    }

    /** 拿到 planId 后按 approved 确认（独立线程，避免与挂起线程同步阻塞）。 */
    private Thread confirmer(PlanConfirmationService svc, LinkedBlockingQueue<String> planIds, boolean approved) {
        Thread t = new Thread(() -> {
            try {
                String planId = planIds.poll(5, TimeUnit.SECONDS);
                svc.confirm("coder", "s1", planId, approved);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        t.start();
        return t;
    }

    // ===== 全局回落 / BYPASS =====

    @Test
    void onActing_shouldPassThrough_whenUnspecifiedAndGlobalBypass() {
        AdminSandboxProperties props = props(SandboxPermissionMode.BYPASS);
        ExecutionModeMiddleware mw = middleware(props, new ExecutionModeRegistry(), new PlanConfirmationService());
        ActingInput input = riskyInput();
        assertSame(input, drive(mw, input), "未指定模式 + 全局 bypass 应原样透传");
    }

    @Test
    void onActing_shouldPassThrough_whenModeBypass_evenIfGlobalHitl() {
        AdminSandboxProperties props = props(SandboxPermissionMode.HITL);
        ExecutionModeRegistry registry = new ExecutionModeRegistry();
        registry.put("coder", "s1", ExecutionMode.BYPASS);
        ExecutionModeMiddleware mw = middleware(props, registry, new PlanConfirmationService());
        ActingInput input = riskyInput();
        assertSame(input, drive(mw, input), "显式 BYPASS 应透传，压过全局 hitl");
    }

    // ===== AUTO =====

    @Test
    void onActing_auto_shouldPassThrough_whenNoChannel() {
        AdminSandboxProperties props = props(SandboxPermissionMode.HITL);
        ExecutionModeRegistry registry = new ExecutionModeRegistry();
        registry.put("coder", "s1", ExecutionMode.AUTO);
        ExecutionModeMiddleware mw = middleware(props, registry, new PlanConfirmationService());
        ActingInput input = riskyInput();
        assertSame(input, drive(mw, input), "AUTO 命中高风险但无通道时放行原始调用交给护栏兜底");
    }

    @Test
    void onActing_auto_shouldExecuteOriginal_whenApproved() {
        AdminSandboxProperties props = props(SandboxPermissionMode.HITL);
        ExecutionModeRegistry registry = new ExecutionModeRegistry();
        registry.put("coder", "s1", ExecutionMode.AUTO);
        PlanConfirmationService svc = new PlanConfirmationService();
        PlanChannel channel = svc.openChannel("coder", "s1");
        confirmer(svc, capturePlanIds(svc, channel), true);

        ActingInput received = drive(middleware(props, registry, svc), riskyInput());
        assertEquals(RISKY_COMMAND, received.toolCalls().get(0).getInput().get("command"), "批准后应原样执行");
    }

    @Test
    void onActing_auto_shouldNeutralize_whenRejected() {
        AdminSandboxProperties props = props(SandboxPermissionMode.HITL);
        ExecutionModeRegistry registry = new ExecutionModeRegistry();
        registry.put("coder", "s1", ExecutionMode.AUTO);
        PlanConfirmationService svc = new PlanConfirmationService();
        PlanChannel channel = svc.openChannel("coder", "s1");
        confirmer(svc, capturePlanIds(svc, channel), false);

        ActingInput received = drive(middleware(props, registry, svc), riskyInput());
        assertEquals(REJECT_NOTICE, received.toolCalls().get(0).getInput().get("command"), "拒绝后风险入参应改写取消");
    }

    @Test
    void onActing_auto_shouldNeutralizeAllWrites_whenBatchModifyRejected() {
        AdminSandboxProperties props = props(SandboxPermissionMode.HITL);
        ExecutionModeRegistry registry = new ExecutionModeRegistry();
        registry.put("coder", "s1", ExecutionMode.AUTO);
        PlanConfirmationService svc = new PlanConfirmationService();
        PlanChannel channel = svc.openChannel("coder", "s1");
        confirmer(svc, capturePlanIds(svc, channel), false);

        ActingInput input = new ActingInput(List.of(
            new ToolUseBlock("id-1", "write_file", Map.of("path", "sessions/x/a.java", "content", "class A {}")),
            new ToolUseBlock("id-2", "write_file", Map.of("path", "sessions/x/b.java", "content", "class B {}")),
            new ToolUseBlock("id-3", "edit_file", Map.of("path", "sessions/x/c.java", "content", "class C {}")),
            new ToolUseBlock("id-4", "create_file", Map.of("path", "sessions/x/d.java", "content", "class D {}"))));
        ActingInput received = drive(middleware(props, registry, svc), input);
        for (ToolUseBlock use : received.toolCalls()) {
            assertEquals(REJECT_NOTICE, use.getInput().get("path"), "批量拒绝后 path 应改写: " + use.getName());
            assertEquals(REJECT_NOTICE, use.getInput().get("content"), "批量拒绝后 content 应改写: " + use.getName());
        }
    }

    // ===== MANUAL =====

    @Test
    void onActing_manual_shouldConfirmEvenNonRisky_andExecuteOnApprove() {
        AdminSandboxProperties props = props(SandboxPermissionMode.BYPASS);
        ExecutionModeRegistry registry = new ExecutionModeRegistry();
        registry.put("coder", "s1", ExecutionMode.MANUAL);
        PlanConfirmationService svc = new PlanConfirmationService();
        PlanChannel channel = svc.openChannel("coder", "s1");
        confirmer(svc, capturePlanIds(svc, channel), true);

        // 纯只读工具在 AUTO 下不会挂起，MANUAL 下必须逐工具确认
        ActingInput input = new ActingInput(List.of(new ToolUseBlock("id-1", "read_file", Map.of("path", "a.java"))));
        ActingInput received = drive(middleware(props, registry, svc), input);
        assertEquals("a.java", received.toolCalls().get(0).getInput().get("path"), "MANUAL 批准后原样执行");
    }

    @Test
    void onActing_manual_shouldNeutralizeAllTools_whenRejected() {
        AdminSandboxProperties props = props(SandboxPermissionMode.BYPASS);
        ExecutionModeRegistry registry = new ExecutionModeRegistry();
        registry.put("coder", "s1", ExecutionMode.MANUAL);
        PlanConfirmationService svc = new PlanConfirmationService();
        PlanChannel channel = svc.openChannel("coder", "s1");
        confirmer(svc, capturePlanIds(svc, channel), false);

        ActingInput input = new ActingInput(List.of(
            new ToolUseBlock("id-1", "read_file", Map.of("path", "a.java")),
            new ToolUseBlock("id-2", "query_db", Map.of("sql", "select 1"))));
        ActingInput received = drive(middleware(props, registry, svc), input);
        assertEquals(REJECT_NOTICE, received.toolCalls().get(0).getInput().get("path"), "MANUAL 拒绝：全部工具整体取消");
        assertEquals(REJECT_NOTICE, received.toolCalls().get(1).getInput().get("sql"), "MANUAL 拒绝：全部工具整体取消");
    }

    // ===== ACCEPT_EDITS =====

    @Test
    void onActing_acceptEdits_shouldPassThrough_whenOnlyBatchWrites() {
        AdminSandboxProperties props = props(SandboxPermissionMode.HITL);
        ExecutionModeRegistry registry = new ExecutionModeRegistry();
        registry.put("coder", "s1", ExecutionMode.ACCEPT_EDITS);
        ExecutionModeMiddleware mw = middleware(props, registry, new PlanConfirmationService());
        // 4 个写入（AUTO 下会触发 BATCH_MODIFY 挂起）；ACCEPT_EDITS 把编辑视为自动放行
        ActingInput input = new ActingInput(List.of(
            new ToolUseBlock("id-1", "write_file", Map.of("path", "a.java")),
            new ToolUseBlock("id-2", "write_file", Map.of("path", "b.java")),
            new ToolUseBlock("id-3", "edit_file", Map.of("path", "c.java")),
            new ToolUseBlock("id-4", "create_file", Map.of("path", "d.java"))));
        assertSame(input, drive(mw, input), "ACCEPT_EDITS 下纯批量编辑应自动放行");
    }

    @Test
    void onActing_acceptEdits_shouldConfirmDelete_andNeutralizeOnReject() {
        AdminSandboxProperties props = props(SandboxPermissionMode.HITL);
        ExecutionModeRegistry registry = new ExecutionModeRegistry();
        registry.put("coder", "s1", ExecutionMode.ACCEPT_EDITS);
        PlanConfirmationService svc = new PlanConfirmationService();
        PlanChannel channel = svc.openChannel("coder", "s1");
        confirmer(svc, capturePlanIds(svc, channel), false);

        // 删除仍需确认，编辑放行；拒绝后仅删除被取消，写入原样保留
        ActingInput input = new ActingInput(List.of(
            new ToolUseBlock("id-1", "delete_file", Map.of("path", "sessions/x/old.java")),
            new ToolUseBlock("id-2", "write_file", Map.of("path", "sessions/x/new.java"))));
        ActingInput received = drive(middleware(props, registry, svc), input);
        assertEquals(REJECT_NOTICE, received.toolCalls().get(0).getInput().get("path"), "删除被拒绝应取消");
        assertEquals("sessions/x/new.java", received.toolCalls().get(1).getInput().get("path"), "编辑放行应保留");
    }

    // ===== PLAN =====

    @Test
    void onActing_plan_shouldBlockMutating_andPassReadonly() {
        AdminSandboxProperties props = props(SandboxPermissionMode.BYPASS);
        ExecutionModeRegistry registry = new ExecutionModeRegistry();
        registry.put("coder", "s1", ExecutionMode.PLAN);
        ExecutionModeMiddleware mw = middleware(props, registry, new PlanConfirmationService());

        ActingInput input = new ActingInput(List.of(
            new ToolUseBlock("id-1", "write_file", Map.of("path", "a.java", "content", "class A {}")),
            new ToolUseBlock("id-2", "read_file", Map.of("path", "b.java"))));
        ActingInput received = drive(mw, input);
        assertEquals(PLAN_BLOCK_NOTICE, received.toolCalls().get(0).getInput().get("path"), "PLAN：mutating 工具 path 拦改");
        assertEquals(PLAN_BLOCK_NOTICE, received.toolCalls().get(0).getInput().get("content"), "PLAN：mutating 工具 content 拦改");
        assertEquals("b.java", received.toolCalls().get(1).getInput().get("path"), "PLAN：只读工具原样放行");
    }
}
