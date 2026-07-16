package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

import java.util.List;

/**
 * {@code test_report} SSE 事件的 data 载荷（需求文档 §4.3.2/§6.2）：沙箱内编译/测试命令
 * （{@code execute} 工具）的一次执行结果，由 {@code TestReportParser} 从 {@code TOOL_RESULT}
 * 输出解析而来。字段契约以需求文档为准，另附 {@link #round}/{@link #exhausted} 两个字段承载
 * 失败自动修复循环的进度（P0-3 需求 2，向后兼容——旧前端忽略未知字段）。
 *
 * @param command        识别出的命令标签（如 {@code mvn test}/{@code mvn compile}/{@code javac}）
 * @param exitCode       进程退出码（0 表示成功）
 * @param success        综合判定是否成功（{@code exitCode==0} 且无失败用例）
 * @param passed         通过用例数（编译类命令无用例概念时为 0）
 * @param failed         失败用例数（{@code Failures + Errors}）
 * @param skipped        跳过用例数
 * @param durationMs     执行耗时毫秒（解析不到时为 {@code null}）
 * @param failureDetails 失败用例/编译错误的结构化摘要清单
 * @param rawOutput      解析降级时透传的原始输出尾段（成功且完整解析时为 {@code null}，避免冗余）
 * @param round          本轮对话内第几次编译/测试执行（1 基，用于修复轮次进度展示）
 * @param exhausted      是否已达失败自动修复轮数上限（仍失败），前端据此提示"已达最大修复轮次"
 * @author owlzhangfq@gmail.com
 */
public record TestReport(
        String command,
        Integer exitCode,
        boolean success,
        int passed,
        int failed,
        int skipped,
        Long durationMs,
        List<String> failureDetails,
        String rawOutput,
        int round,
        boolean exhausted) {

    /** 复制本报告并填充修复轮次进度（{@code round}/{@code exhausted}），其余字段不变。 */
    public TestReport withProgress(int round, boolean exhausted) {
        return new TestReport(command, exitCode, success, passed, failed, skipped,
            durationMs, failureDetails, rawOutput, round, exhausted);
    }
}
