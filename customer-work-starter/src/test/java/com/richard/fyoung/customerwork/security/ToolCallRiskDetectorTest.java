package com.richard.fyoung.customerwork.security;

import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolCallRiskDetector} 单测（规则算法唯一权威的主战场）：破坏性判定、各风险分支、
 * mutating 判定的黑白名单优先级、两种取消改写。
 *
 * <p>规则取值与 admin 沙箱/执行模式配置的默认值一致，保证下沉前后判定结论逐条等价。</p>
 * @author owlzhangfq@gmail.com
 */
class ToolCallRiskDetectorTest {

    private static final ToolCallRiskRules DEFAULT_RULES = new ToolCallRiskRules(
        List.of("rm\\s+-rf", "\\.git[/\\\\]", "del\\s+/[fs]", "format\\s",
            "(^|[\\s/])/etc(/|\\s|$)", "(^|[\\s/])/root(/|\\s|$)"),
        List.of("\\brm\\b", "\\brmdir\\b", "\\bmv\\b", "mvn\\s+clean", "gradle\\s+clean",
            "git\\s+(reset|checkout|clean|revert|rebase)\\b"),
        List.of("pom\\.xml", "build\\.gradle", "package\\.json", "build\\.gradle\\.kts"),
        3,
        List.of("exec", "run", "shell", "command", "bash", "terminal"),
        List.of(),
        List.of());

    private final ToolCallRiskDetector detector = new ToolCallRiskDetector(DEFAULT_RULES);

    private ToolUseBlock tool(String name, Map<String, Object> input) {
        return new ToolUseBlock("call-1", name, input);
    }

    private Map<String, Object> param(String key, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    @Test
    void matchesDestructive_shouldMatchGuardPatternsOnly() {
        assertTrue(detector.matchesDestructive("rm -rf sessions/x"));
        assertTrue(detector.matchesDestructive("touch sessions/x/.git/config"));
        assertFalse(detector.matchesDestructive("mvn test"));
        assertFalse(detector.matchesDestructive(null), "null 入参不判定为破坏性");
    }

    @Test
    void assess_shouldFlagDeleteTool() {
        Optional<ToolCallRisk> risk = detector.assess(tool("delete_file", param("path", "sessions/x/Foo.java")));
        assertTrue(risk.isPresent());
        assertEquals(ToolCallRiskType.DELETE, risk.get().type());
        assertEquals("sessions/x/Foo.java", risk.get().target());
    }

    @Test
    void assess_shouldFlagDestructiveCommand_overNonReadonly() {
        Optional<ToolCallRisk> risk = detector.assess(tool("shell_execute", param("command", "rm -rf /tmp/x")));
        assertTrue(risk.isPresent());
        assertEquals(ToolCallRiskType.RUN_COMMAND, risk.get().type());
        assertEquals("执行破坏性命令", risk.get().reason(), "破坏性优先于非只读命令");
    }

    @Test
    void assess_shouldFlagNonReadonlyCommand_mvnClean() {
        Optional<ToolCallRisk> risk = detector.assess(tool("shell_execute", param("command", "mvn clean install")));
        assertTrue(risk.isPresent());
        assertEquals(ToolCallRiskType.RUN_COMMAND, risk.get().type());
        assertEquals("执行非只读命令", risk.get().reason());
    }

    @Test
    void assess_shouldFlagDependencyFileEdit() {
        Optional<ToolCallRisk> risk = detector.assess(tool("write_file", param("path", "sessions/x/pom.xml")));
        assertTrue(risk.isPresent());
        assertEquals(ToolCallRiskType.MODIFY_DEPENDENCY, risk.get().type());
        assertEquals("sessions/x/pom.xml", risk.get().target());
    }

    @Test
    void assess_shouldNotFlagOrdinaryWriteOrReadonlyCommand() {
        assertTrue(detector.assess(tool("write_file", param("path", "sessions/x/Foo.java"))).isEmpty());
        assertTrue(detector.assess(tool("shell_execute", param("command", "mvn test"))).isEmpty());
        assertTrue(detector.assess((ToolUseBlock) null).isEmpty(), "null 工具调用不判风险");
    }

    @Test
    void assess_batch_shouldFlagWhenWriteToolsExceedThreshold() {
        // 4 个 write 工具 > 阈值 3 → 追加一条 BATCH_MODIFY
        List<ToolUseBlock> calls = List.of(
            tool("write_file", param("path", "a.java")),
            tool("write_file", param("path", "b.java")),
            tool("edit_file", param("path", "c.java")),
            tool("create_file", param("path", "d.java")));
        List<ToolCallRisk> risks = detector.assess(calls);
        ToolCallRisk batch = risks.stream()
            .filter(r -> r.type() == ToolCallRiskType.BATCH_MODIFY)
            .findFirst()
            .orElseThrow();
        assertEquals("4 files", batch.target());
    }

    @Test
    void assess_batch_shouldNotFlagWhenWithinThreshold() {
        List<ToolUseBlock> calls = List.of(
            tool("write_file", param("path", "a.java")),
            tool("write_file", param("path", "b.java")));
        assertTrue(detector.assess(calls).isEmpty());
        assertTrue(detector.assess((List<ToolUseBlock>) null).isEmpty(), "空清单不判风险");
    }

    @Test
    void isHighRisk_shouldFollowAssess() {
        assertTrue(detector.isHighRisk(tool("delete_file", param("path", "a.java"))));
        assertFalse(detector.isHighRisk(tool("write_file", param("path", "a.java"))));
    }

    @Test
    void isWriteToolName_shouldMatchWriteKeywords() {
        assertTrue(detector.isWriteToolName("edit_file"));
        assertFalse(detector.isWriteToolName("read_file"));
    }

    @Test
    void isMutatingTool_shouldFlagWriteDeleteAndExecTools() {
        assertTrue(detector.isMutatingTool(tool("write_file", param("path", "a.java"))));
        assertTrue(detector.isMutatingTool(tool("delete_file", param("path", "a.java"))));
        assertTrue(detector.isMutatingTool(tool("shell_execute", param("command", "ls"))), "命令执行关键字命中");
        assertTrue(detector.isMutatingTool(tool("read_file", param("command", "rm -rf x"))), "入参命中破坏性命令");
    }

    @Test
    void isMutatingTool_shouldPassReadonlyTools() {
        assertFalse(detector.isMutatingTool(tool("read_file", param("path", "a.java"))));
        assertFalse(detector.isMutatingTool(tool("search_code", param("q", "foo"))));
        assertFalse(detector.isMutatingTool(tool("list_dir", param("path", "."))));
        assertFalse(detector.isMutatingTool(null));
    }

    @Test
    void isMutatingTool_shouldRespectPatternListPriority() {
        ToolCallRiskDetector custom = new ToolCallRiskDetector(new ToolCallRiskRules(
            DEFAULT_RULES.destructivePatterns(), DEFAULT_RULES.confirmableCommandPatterns(),
            DEFAULT_RULES.dependencyFilePatterns(), DEFAULT_RULES.batchModifyThreshold(),
            DEFAULT_RULES.execToolKeywords(),
            List.of("^safe_exec$"),
            List.of("^report_.*")));

        assertFalse(custom.isMutatingTool(tool("safe_exec", param("cmd", "ls"))), "白名单命中应放行");
        assertTrue(custom.isMutatingTool(tool("report_status", param("q", "x"))), "黑名单优先于启发式只读判定");
    }

    @Test
    void describeTool_shouldContainToolNameAndInput() {
        String desc = detector.describeTool(tool("read_file", param("path", "a.java")));
        assertTrue(desc.contains("read_file"), "描述应含工具名");
        assertTrue(desc.contains("a.java"), "描述应含入参摘要");
        assertEquals("", detector.describeTool(null));
    }

    @Test
    void describeTool_shouldTruncateOverlongInput() {
        String longValue = "x".repeat(500);
        String desc = detector.describeTool(tool("write_file", param("content", longValue)));
        assertTrue(desc.endsWith("..."), "超长描述应被截断");
        assertEquals(203, desc.length(), "截断到 200 字符 + 省略号");
    }

    @Test
    void neutralize_shouldRewriteRiskyCommandParam_keepingBenignOnes() {
        Map<String, Object> input = param("command", "rm -rf x");
        input.put("description", "clean up");
        ToolUseBlock rewritten = detector.neutralize(tool("shell_execute", input), "[REJECTED]");
        assertEquals("[REJECTED]", rewritten.getInput().get("command"));
        assertEquals("clean up", rewritten.getInput().get("description"), "非风险入参保持原样");
    }

    @Test
    void neutralize_shouldRewriteDeleteToolParam() {
        ToolUseBlock rewritten = detector.neutralize(tool("delete_file", param("path", "sessions/x/Foo.java")), "[REJECTED]");
        assertEquals("[REJECTED]", rewritten.getInput().get("path"));
    }

    @Test
    void neutralize_shouldReturnSameBlock_whenNoRiskyParam() {
        ToolUseBlock use = tool("write_file", param("path", "a.java"));
        assertSame(use, detector.neutralize(use, "[REJECTED]"), "未命中任何风险入参应返回原块");
    }

    @Test
    void neutralizeAllStringParams_shouldRewriteEveryStringParam() {
        Map<String, Object> input = param("path", "a.java");
        input.put("content", "class A {}");
        input.put("retries", 3);
        ToolUseBlock rewritten = detector.neutralizeAllStringParams(tool("write_file", input), "[REJECTED]");
        assertEquals("[REJECTED]", rewritten.getInput().get("path"));
        assertEquals("[REJECTED]", rewritten.getInput().get("content"));
        assertEquals(3, rewritten.getInput().get("retries"), "非字符串入参保持原样");
    }

    @Test
    void emptyRules_shouldDetectNothing() {
        ToolCallRiskDetector empty = new ToolCallRiskDetector(
            new ToolCallRiskRules(null, null, null, Integer.MAX_VALUE, null, null, null));
        assertFalse(empty.matchesDestructive("rm -rf /"));
        assertTrue(empty.assess(tool("shell_execute", param("command", "rm -rf /"))).isEmpty());
        assertFalse(empty.isMutatingTool(tool("shell_execute", param("command", "rm -rf /"))),
            "关键字规则为空时命令执行类工具不再判 mutating");
    }
}
