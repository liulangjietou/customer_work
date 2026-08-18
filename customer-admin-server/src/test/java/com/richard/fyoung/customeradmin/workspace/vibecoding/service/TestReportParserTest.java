package com.richard.fyoung.customeradmin.workspace.vibecoding.service;

import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.TestReport;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TestReportParser} 单测：从 {@code execute} 工具的 TOOL_RESULT 展示文本解析结构化
 * 编译/测试报告——命令识别、用例计数、失败明细、退出码、降级边界。
 * @author owlzhangfq@gmail.com
 */
class TestReportParserTest {

    /** describeToolResult 对 execute 工具的固定前缀（与 ChatService 保持一致）。 */
    private static final String PREFIX = "工具「execute」返回：";

    @Test
    void shouldParseMavenTestSuccess() {
        String text = PREFIX + "Exit code: 0\n"
            + "[INFO] Results:\n"
            + "[INFO] \n"
            + "[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0\n"
            + "[INFO] \n"
            + "[INFO] BUILD SUCCESS\n"
            + "[INFO] Total time:  8.523 s\n";

        TestReport report = TestReportParser.parse(text).orElseThrow();

        assertEquals("mvn test", report.command());
        assertEquals(0, report.exitCode());
        assertTrue(report.success());
        assertEquals(12, report.passed());
        assertEquals(0, report.failed());
        assertEquals(0, report.skipped());
        assertEquals(8523L, report.durationMs());
        assertTrue(report.failureDetails().isEmpty());
        assertNull(report.rawOutput(), "成功且完整解析时不透传 rawOutput");
    }

    @Test
    void shouldParseMavenTestFailure_withFailureDetails() {
        String text = PREFIX + "Exit code: 1\n"
            + "[ERROR] testBar(com.example.FooTest)  Time elapsed: 0.01 s  <<< FAILURE!\n"
            + "org.opentest4j.AssertionFailedError: expected: <1> but was: <2>\n"
            + "[INFO] \n"
            + "[ERROR] Tests run: 5, Failures: 2, Errors: 0, Skipped: 1\n"
            + "[INFO] BUILD FAILURE\n";

        TestReport report = TestReportParser.parse(text).orElseThrow();

        assertEquals("mvn test", report.command());
        assertEquals(1, report.exitCode());
        assertFalse(report.success());
        assertEquals(2, report.failed());
        assertEquals(1, report.skipped());
        assertEquals(2, report.passed(), "passed = run(5) - failed(2) - skipped(1)");
        assertTrue(report.failureDetails().stream().anyMatch(d -> d.contains("testBar")), "应捕获失败用例明细");
        assertNotNull(report.rawOutput(), "失败时应透传原始输出兜底");
    }

    @Test
    void shouldTakeLastSummaryLine_asAggregateTotal() {
        // Maven 会为每个测试类各打一行，末尾 Results 处再打一行总计——取最后一条
        String text = PREFIX + "Exit code: 0\n"
            + "[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0\n"
            + "[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0\n"
            + "[INFO] Results:\n"
            + "[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0\n";

        TestReport report = TestReportParser.parse(text).orElseThrow();
        assertEquals(7, report.passed());
    }

    @Test
    void shouldParseJavacCompileError() {
        String text = PREFIX + "Exit code: 1\n"
            + "Foo.java:3: error: ';' expected\n"
            + "    int x = 1\n"
            + "             ^\n"
            + "1 error\n";

        TestReport report = TestReportParser.parse(text).orElseThrow();

        assertEquals("javac", report.command());
        assertFalse(report.success());
        assertEquals(1, report.failed());
        assertTrue(report.failureDetails().stream().anyMatch(d -> d.contains("Foo.java:3")));
    }

    @Test
    void shouldRecognizeMavenCompileBuild_withoutTests() {
        String text = PREFIX + "Exit code: 0\n"
            + "[INFO] Compiling 3 source files\n"
            + "[INFO] BUILD SUCCESS\n"
            + "[INFO] Total time:  2.1 s\n";

        TestReport report = TestReportParser.parse(text).orElseThrow();
        assertEquals("mvn", report.command());
        assertTrue(report.success());
        assertEquals(0, report.passed());
        assertEquals(2100L, report.durationMs());
    }

    @Test
    void shouldReturnEmpty_forNonExecuteTool() {
        assertEquals(Optional.empty(), TestReportParser.parse("工具「write_file」返回：ok"));
    }

    @Test
    void shouldReturnEmpty_forPlainShellCommand() {
        // execute 工具但不是编译/测试命令（普通 ls），不产出 test_report
        assertEquals(Optional.empty(), TestReportParser.parse(PREFIX + "Exit code: 0\nfile1.txt\nfile2.txt"));
    }

    @Test
    void shouldReturnEmpty_forBlankOrNull() {
        assertEquals(Optional.empty(), TestReportParser.parse(null));
        assertEquals(Optional.empty(), TestReportParser.parse(""));
    }

    @Test
    void parseCommand_shouldReportSuccessfulSilentJavac() {
        TestReport report = TestReportParser.parseCommand("javac Hello.java", "", 0, 120L).orElseThrow();

        assertEquals("javac", report.command());
        assertTrue(report.success());
        assertEquals(0, report.failed());
        assertEquals(120L, report.durationMs());
    }

    @Test
    void parseCommand_shouldUseRealMavenCommand_withoutDependingOnBuildBanner() {
        TestReport report = TestReportParser.parseCommand("./mvnw test -q",
            "Tests run: 3, Failures: 1, Errors: 0, Skipped: 0", 1, 2000L).orElseThrow();

        assertEquals("mvn test", report.command());
        assertFalse(report.success());
        assertEquals(2, report.passed());
        assertEquals(1, report.failed());
    }
}
