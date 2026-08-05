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
 * {@link SandboxRiskDetector} 薄壳单测：验证 admin 配置默认值绑进 starter 规则后判定结论不变，
 * 以及 starter 风险类型 → {@code plan} 事件 {@link PlanAction} 的映射。
 *
 * <p>规则算法的分支矩阵在 starter 的 {@code ToolCallRiskDetectorTest}，此处不重复。</p>
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
    void assess_shouldMapDeleteRiskToPlanAction() {
        Optional<PlanAction> action = detector.assess(tool("delete_file", param("path", "sessions/x/Foo.java")));
        assertTrue(action.isPresent());
        assertEquals("DELETE", action.get().type());
        assertEquals("sessions/x/Foo.java", action.get().target());
        assertEquals("删除文件", action.get().reason());
    }

    @Test
    void assess_shouldMapCommandRiskToPlanAction() {
        Optional<PlanAction> destructive = detector.assess(tool("shell_execute", param("command", "rm -rf /tmp/x")));
        assertTrue(destructive.isPresent());
        assertEquals("RUN_COMMAND", destructive.get().type());
        assertEquals("执行破坏性命令", destructive.get().reason());

        Optional<PlanAction> nonReadonly = detector.assess(tool("shell_execute", param("command", "mvn clean install")));
        assertTrue(nonReadonly.isPresent());
        assertEquals("RUN_COMMAND", nonReadonly.get().type());
    }

    @Test
    void assess_shouldMapDependencyRiskToPlanAction() {
        Optional<PlanAction> action = detector.assess(tool("write_file", param("path", "sessions/x/pom.xml")));
        assertTrue(action.isPresent());
        assertEquals(SandboxRiskDetector.ACTION_MODIFY_DEPENDENCY, action.get().type());
    }

    @Test
    void assess_batch_shouldMapBatchModifyRiskToPlanAction() {
        // 4 个 write 工具 > 默认阈值 3 → 追加一条 BATCH_MODIFY
        List<ToolUseBlock> calls = List.of(
            tool("write_file", param("path", "a.java")),
            tool("write_file", param("path", "b.java")),
            tool("edit_file", param("path", "c.java")),
            tool("create_file", param("path", "d.java")));
        List<PlanAction> actions = detector.assess(calls);
        assertTrue(actions.stream().anyMatch(a -> SandboxRiskDetector.ACTION_BATCH_MODIFY.equals(a.type())));

        assertTrue(detector.assess(List.of(tool("write_file", param("path", "a.java")))).isEmpty(),
            "阈值内的普通写入不产生动作");
    }

    @Test
    void manualToolAction_shouldDescribeAsExecuteTool() {
        PlanAction action = detector.manualToolAction(tool("read_file", param("path", "a.java")));
        assertEquals(SandboxRiskDetector.ACTION_EXECUTE_TOOL, action.type());
        assertTrue(action.target().contains("read_file"), "target 应含工具名");
    }

    @Test
    void delegation_shouldKeepRiskAndRewriteBehaviour() {
        assertTrue(detector.isHighRisk(tool("delete_file", param("path", "a.java"))));
        assertTrue(detector.isWriteToolName("edit_file"));
        assertTrue(detector.isMutatingTool(tool("shell_execute", param("command", "ls"))));
        assertFalse(detector.isMutatingTool(tool("read_file", param("path", "a.java"))));

        ToolUseBlock rewritten = detector.neutralize(tool("shell_execute", param("command", "rm -rf x")), "[REJECTED]");
        assertEquals("[REJECTED]", rewritten.getInput().get("command"));

        ToolUseBlock cancelled = detector.neutralizeAllStringParams(tool("write_file", param("path", "a.java")), "[REJECTED]");
        assertEquals("[REJECTED]", cancelled.getInput().get("path"));
    }
}
