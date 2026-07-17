package com.richard.fyoung.customeradmin.workspace.runtime;

import com.richard.fyoung.customeradmin.config.AdminSandboxProperties;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.PlanAction;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SandboxRiskDetector} 单测：高风险动作判定（删除/命令/依赖/批量）、破坏性判定与护栏同源、拒绝改写。
 * @author owlzhangfq@gmail.com
 */
class SandboxRiskDetectorTest {

    private final SandboxRiskDetector detector = new SandboxRiskDetector(new AdminSandboxProperties());

    private ToolUseBlock tool(String name, Map<String, Object> input) {
        return new ToolUseBlock("call-1", name, input);
    }

    private Map<String, Object> param(String key, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    @Test
    void matchesDestructive_shouldStayInSyncWithGuardPatterns() {
        assertTrue(detector.matchesDestructive("rm -rf sessions/x"));
        assertTrue(detector.matchesDestructive("touch sessions/x/.git/config"));
        assertFalse(detector.matchesDestructive("mvn test"));
    }

    @Test
    void assess_shouldFlagDeleteTool() {
        Optional<PlanAction> action = detector.assess(tool("delete_file", param("path", "sessions/x/Foo.java")));
        assertTrue(action.isPresent());
        assertEquals("DELETE", action.get().type());
        assertEquals("sessions/x/Foo.java", action.get().target());
    }

    @Test
    void assess_shouldFlagNonReadonlyCommand_mvnClean() {
        Optional<PlanAction> action = detector.assess(tool("shell_execute", param("command", "mvn clean install")));
        assertTrue(action.isPresent());
        assertEquals("RUN_COMMAND", action.get().type());
    }

    @Test
    void assess_shouldFlagDestructiveCommand_asRunCommand() {
        Optional<PlanAction> action = detector.assess(tool("shell_execute", param("command", "rm -rf /tmp/x")));
        assertTrue(action.isPresent());
        assertEquals("RUN_COMMAND", action.get().type());
        assertEquals("执行破坏性命令", action.get().reason());
    }

    @Test
    void assess_shouldFlagDependencyFileEdit() {
        Optional<PlanAction> action = detector.assess(tool("write_file", param("path", "sessions/x/pom.xml")));
        assertTrue(action.isPresent());
        assertEquals("MODIFY_DEPENDENCY", action.get().type());
    }

    @Test
    void assess_shouldNotFlagOrdinaryWrite() {
        assertTrue(detector.assess(tool("write_file", param("path", "sessions/x/Foo.java"))).isEmpty());
        assertTrue(detector.assess(tool("shell_execute", param("command", "mvn test"))).isEmpty());
    }

    @Test
    void assess_batch_shouldFlagWhenWriteToolsExceedThreshold() {
        // 4 个 write 工具 > 阈值 3 → 追加一条 BATCH_MODIFY
        List<ToolUseBlock> calls = List.of(
            tool("write_file", param("path", "a.java")),
            tool("write_file", param("path", "b.java")),
            tool("edit_file", param("path", "c.java")),
            tool("create_file", param("path", "d.java")));
        List<PlanAction> actions = detector.assess(calls);
        assertTrue(actions.stream().anyMatch(a -> a.type().equals("BATCH_MODIFY")));
    }

    @Test
    void assess_batch_shouldNotFlagWhenWithinThreshold() {
        List<ToolUseBlock> calls = List.of(
            tool("write_file", param("path", "a.java")),
            tool("write_file", param("path", "b.java")));
        assertTrue(detector.assess(calls).isEmpty());
    }

    @Test
    void neutralize_shouldRewriteRiskyCommandParam() {
        ToolUseBlock rewritten = detector.neutralize(tool("shell_execute", param("command", "rm -rf x")), "[REJECTED]");
        assertEquals("[REJECTED]", rewritten.getInput().get("command"));
    }

    @Test
    void neutralize_shouldRewriteDeleteToolParam() {
        ToolUseBlock rewritten = detector.neutralize(tool("delete_file", param("path", "sessions/x/Foo.java")), "[REJECTED]");
        assertEquals("[REJECTED]", rewritten.getInput().get("path"));
    }
}
