package com.richard.fyoung.customeradmin.workspace.vibecoding.service;

import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.TestReport;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 沙箱编译/测试报告解析器（需求文档 §4.3.2）：把框架 {@code execute} 工具的 {@code TOOL_RESULT}
 * 输出解析成结构化 {@link TestReport}。纯函数、无状态，单测可独立覆盖。
 *
 * <h3>输入约定</h3>
 * <p>{@link ChatService#describeToolResult} 把工具结果格式化成
 * {@code 工具「{name}」返回：{output}}，而框架 {@code ShellExecuteTool}（工具名固定为
 * {@code execute}）的 {@code output} 又固定以 {@code Exit code: N\n} 打头。本解析器据此两层特征
 * 识别"沙箱内命令执行"结果，再按输出内容判定是否为编译/测试命令。</p>
 *
 * <h3>命令识别（无法从结果块拿到原始命令，只能按输出特征反推，见需求"识别命令特征"）</h3>
 * <ul>
 *   <li>含 Maven Surefire 汇总行 {@code Tests run: X, Failures: Y, ...} → {@code mvn test}；</li>
 *   <li>含 {@code BUILD SUCCESS}/{@code BUILD FAILURE} 但无用例汇总 → {@code mvn}（编译/打包）；</li>
 *   <li>含 {@code X.java:line: error} 编译错误 → {@code javac}；</li>
 *   <li>其余（普通 shell 命令、成功且无输出的 javac 等）→ 不视为编译/测试执行，返回空。</li>
 * </ul>
 *
 * <p><b>已知局限</b>：用例计数取输出中<b>最后一条</b> Surefire 汇总行——多模块 reactor 构建里每个
 * 模块的 Results 各打一条，最后一条只反映最后一个模块的计数，不是全 reactor 总和。VibeCoding 会话
 * 产物是单模块小工程，该局限不影响本场景；如后续支持多模块工程需改为逐模块累加。</p>
 */
public final class TestReportParser {

    /**
     * {@code execute} 工具结果在 {@link ChatService#describeToolResult} 格式化后的固定前缀。
     * 与该方法的拼接格式保持同步（工具名取自框架 {@code ShellExecuteTool.NAME="execute"}）。
     */
    private static final String EXECUTE_RESULT_PREFIX = "工具「execute」返回：";
    private static final Pattern EXIT_CODE_PATTERN = Pattern.compile("Exit code:\\s*(-?\\d+)");
    /** Maven Surefire 汇总行（每个测试类一条 + 末尾 Results 处一条总计，取最后一条为总计）。 */
    private static final Pattern TEST_SUMMARY_PATTERN = Pattern.compile(
        "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");
    /** Maven 总耗时行（如 {@code Total time:  8.523 s} / {@code Total time: 01:02 min}——只解析秒制）。 */
    private static final Pattern MAVEN_TIME_PATTERN = Pattern.compile("Total time:\\s*([\\d.]+)\\s*s");
    /** 每个失败/出错用例运行时打印的行尾标记。 */
    private static final Pattern SUREFIRE_FAILURE_PATTERN = Pattern.compile(".*<<< (FAILURE|ERROR)!.*");
    /** javac 编译错误行（兼容中英文 error 标签）。 */
    private static final Pattern JAVAC_ERROR_PATTERN = Pattern.compile(".*\\.java:\\d+:\\s*(error|错误).*");

    private static final int MAX_FAILURE_DETAILS = 20;
    private static final int MAX_RAW_OUTPUT_CHARS = 2000;
    private static final String COMMAND_MVN_TEST = "mvn test";
    private static final String COMMAND_MVN = "mvn";
    private static final String COMMAND_JAVAC = "javac";

    private TestReportParser() {
    }

    /**
     * 解析一条 {@code TOOL_RESULT} 展示文本。非 {@code execute} 工具结果、或非编译/测试命令，返回空。
     * 返回的 {@link TestReport} 的 {@code round=1}、{@code exhausted=false} 为占位，由调用方
     * （{@link VibeCodingService}）按本轮对话执行序补全修复轮次进度。
     */
    public static Optional<TestReport> parse(String toolResultText) {
        if (!StringUtils.hasText(toolResultText) || !toolResultText.startsWith(EXECUTE_RESULT_PREFIX)) {
            return Optional.empty();
        }
        String output = toolResultText.substring(EXECUTE_RESULT_PREFIX.length());
        Integer exitCode = parseExitCode(output);

        boolean hasTestSummary = TEST_SUMMARY_PATTERN.matcher(output).find();
        boolean isMavenBuild = output.contains("BUILD SUCCESS") || output.contains("BUILD FAILURE");
        boolean isJavac = JAVAC_ERROR_PATTERN.matcher(output).find();
        if (!hasTestSummary && !isMavenBuild && !isJavac) {
            // 不是可识别的编译/测试命令（普通 shell 命令、成功静默的 javac 等），不产出 test_report
            return Optional.empty();
        }

        int passed = 0;
        int failed = 0;
        int skipped = 0;
        List<String> failureDetails = new ArrayList<>();
        String command;
        if (hasTestSummary) {
            command = COMMAND_MVN_TEST;
            int[] counts = parseTestCounts(output);
            int run = counts[0];
            int failures = counts[1];
            int errors = counts[2];
            skipped = counts[3];
            failed = failures + errors;
            passed = Math.max(0, run - failed - skipped);
            failureDetails = collectFailureDetails(output, SUREFIRE_FAILURE_PATTERN);
        } else if (isMavenBuild) {
            command = COMMAND_MVN;
            failureDetails = collectFailureDetails(output, JAVAC_ERROR_PATTERN);
        } else {
            command = COMMAND_JAVAC;
            failed = 1; // javac 错误块只在编译失败时出现，用例概念不适用，用 failed=1 表达"编译失败"
            failureDetails = collectFailureDetails(output, JAVAC_ERROR_PATTERN);
        }

        boolean success = exitCode != null && exitCode == 0 && failed == 0;
        Long durationMs = parseDurationMs(output);
        String rawOutput = success ? null : truncateTail(output);
        return Optional.of(new TestReport(command, exitCode, success, passed, failed, skipped,
            durationMs, failureDetails, rawOutput, 1, false));
    }

    private static Integer parseExitCode(String output) {
        Matcher m = EXIT_CODE_PATTERN.matcher(output);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /** 取最后一条 Surefire 汇总行（Maven 末尾 Results 处的总计），返回 {@code [run, failures, errors, skipped]}。 */
    private static int[] parseTestCounts(String output) {
        Matcher m = TEST_SUMMARY_PATTERN.matcher(output);
        int[] counts = new int[4];
        while (m.find()) {
            counts[0] = Integer.parseInt(m.group(1));
            counts[1] = Integer.parseInt(m.group(2));
            counts[2] = Integer.parseInt(m.group(3));
            counts[3] = Integer.parseInt(m.group(4));
        }
        return counts;
    }

    private static Long parseDurationMs(String output) {
        Matcher m = MAVEN_TIME_PATTERN.matcher(output);
        if (m.find()) {
            try {
                return (long) (Double.parseDouble(m.group(1)) * 1000);
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    /** 按给定正则收集失败明细行（去重、去掉 Maven 的 {@code [ERROR]} 前缀、限量）。 */
    private static List<String> collectFailureDetails(String output, Pattern pattern) {
        Set<String> details = new LinkedHashSet<>();
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (pattern.matcher(trimmed).matches()) {
                details.add(trimmed.replaceFirst("^\\[ERROR]\\s*", ""));
                if (details.size() >= MAX_FAILURE_DETAILS) {
                    break;
                }
            }
        }
        return new ArrayList<>(details);
    }

    private static String truncateTail(String output) {
        if (output.length() <= MAX_RAW_OUTPUT_CHARS) {
            return output;
        }
        return "…（已截断，仅展示末尾 " + MAX_RAW_OUTPUT_CHARS + " 字符）\n"
            + output.substring(output.length() - MAX_RAW_OUTPUT_CHARS);
    }
}
