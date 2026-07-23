package com.richard.fyoung.customeradmin.workspace.runtime.mode;

import com.richard.fyoung.customeradmin.config.AdminExecutionModeProperties;
import com.richard.fyoung.customeradmin.config.AdminSandboxProperties;
import com.richard.fyoung.customeradmin.workspace.runtime.SandboxRiskDetector;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.PlanAction;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExecutionModePolicy} 全矩阵单测：5 模式 × 各动作类型 + 未指定回落。决策纯函数，是五档语义的权威。
 * @author owlzhangfq@gmail.com
 */
class ExecutionModePolicyTest {

    private final ExecutionModePolicy policy =
        new ExecutionModePolicy(new SandboxRiskDetector(new AdminSandboxProperties(), new AdminExecutionModeProperties()));

    private ToolUseBlock tool(String name, Map<String, Object> input) {
        return new ToolUseBlock("call-1", name, input);
    }

    private ToolUseBlock write(String path) {
        return tool("write_file", Map.of("path", path, "content", "x"));
    }

    // ===== 空/BYPASS =====

    @Test
    void decide_shouldPass_whenNoToolCalls() {
        assertEquals(ModeDecision.Kind.PASS, policy.decide(ExecutionMode.AUTO, List.of()).kind());
    }

    @Test
    void decide_bypass_shouldAlwaysPass() {
        ModeDecision d = policy.decide(ExecutionMode.BYPASS, List.of(tool("shell_execute", Map.of("command", "rm -rf x"))));
        assertEquals(ModeDecision.Kind.PASS, d.kind());
    }

    // ===== AUTO =====

    @Test
    void decide_auto_shouldPass_whenNoRisk() {
        assertEquals(ModeDecision.Kind.PASS, policy.decide(ExecutionMode.AUTO, List.of(write("a.java"))).kind());
    }

    @Test
    void decide_auto_shouldConfirm_whenHighRiskCommand() {
        ModeDecision d = policy.decide(ExecutionMode.AUTO, List.of(tool("shell_execute", Map.of("command", "rm -rf x"))));
        assertEquals(ModeDecision.Kind.CONFIRM, d.kind());
        assertEquals(ModeDecision.Neutralization.RISK_BASED, d.neutralization());
    }

    @Test
    void decide_auto_shouldFlagBatchModify_whenWritesExceedThreshold() {
        ModeDecision d = policy.decide(ExecutionMode.AUTO,
            List.of(write("a.java"), write("b.java"), write("c.java"), write("d.java")));
        assertEquals(ModeDecision.Kind.CONFIRM, d.kind());
        assertTrue(d.batchModify(), "4 个写入 > 阈值应标记 batchModify");
    }

    // ===== ACCEPT_EDITS =====

    @Test
    void decide_acceptEdits_shouldPass_whenOnlyBatchWrites() {
        ModeDecision d = policy.decide(ExecutionMode.ACCEPT_EDITS,
            List.of(write("a.java"), write("b.java"), write("c.java"), write("d.java")));
        assertEquals(ModeDecision.Kind.PASS, d.kind(), "编辑类（含批量）应过滤放行");
    }

    @Test
    void decide_acceptEdits_shouldPass_whenDependencyEdit() {
        ModeDecision d = policy.decide(ExecutionMode.ACCEPT_EDITS, List.of(tool("write_file", Map.of("path", "pom.xml"))));
        assertEquals(ModeDecision.Kind.PASS, d.kind(), "MODIFY_DEPENDENCY 视为编辑应放行");
    }

    @Test
    void decide_acceptEdits_shouldConfirm_whenDelete() {
        ModeDecision d = policy.decide(ExecutionMode.ACCEPT_EDITS, List.of(tool("delete_file", Map.of("path", "x.java"))));
        assertEquals(ModeDecision.Kind.CONFIRM, d.kind());
        assertFalse(d.batchModify(), "过滤 BATCH_MODIFY 后不应再标批量");
        assertEquals("DELETE", d.actions().get(0).type());
    }

    @Test
    void decide_acceptEdits_shouldConfirm_whenRunCommand() {
        ModeDecision d = policy.decide(ExecutionMode.ACCEPT_EDITS,
            List.of(tool("shell_execute", Map.of("command", "mvn clean install"))));
        assertEquals(ModeDecision.Kind.CONFIRM, d.kind(), "RUN_COMMAND 仍需确认");
    }

    // ===== MANUAL =====

    @Test
    void decide_manual_shouldConfirmEveryTool_asExecuteTool() {
        ModeDecision d = policy.decide(ExecutionMode.MANUAL,
            List.of(tool("read_file", Map.of("path", "a.java")), tool("query_db", Map.of("sql", "select 1"))));
        assertEquals(ModeDecision.Kind.CONFIRM, d.kind());
        assertEquals(ModeDecision.Neutralization.ALL_TOOLS, d.neutralization());
        assertEquals(2, d.actions().size());
        for (PlanAction action : d.actions()) {
            assertEquals(SandboxRiskDetector.ACTION_EXECUTE_TOOL, action.type());
        }
    }

    // ===== PLAN =====

    @Test
    void decide_plan_shouldFlagMutating_andPassReadonly() {
        ModeDecision d = policy.decide(ExecutionMode.PLAN,
            List.of(write("a.java"), tool("read_file", Map.of("path", "b.java")),
                tool("shell_execute", Map.of("command", "ls")), tool("search_code", Map.of("q", "x"))));
        assertEquals(ModeDecision.Kind.PLAN_BLOCK, d.kind());
        List<Boolean> flags = d.mutatingFlags();
        assertTrue(flags.get(0), "write_file 是 mutating");
        assertFalse(flags.get(1), "read_file 只读");
        assertTrue(flags.get(2), "shell_execute 命中命令执行关键字，mutating");
        assertFalse(flags.get(3), "search_code 只读");
    }

    // ===== 未指定回落 =====

    @Test
    void decide_null_shouldFallBackToAuto() {
        ModeDecision pass = policy.decide(null, List.of(write("a.java")));
        assertEquals(ModeDecision.Kind.PASS, pass.kind(), "null 回落 AUTO：普通写入放行");
        ModeDecision confirm = policy.decide(null, List.of(tool("shell_execute", Map.of("command", "rm -rf x"))));
        assertEquals(ModeDecision.Kind.CONFIRM, confirm.kind(), "null 回落 AUTO：高风险确认");
    }
}
