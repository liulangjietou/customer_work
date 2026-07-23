package com.richard.fyoung.customeradmin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 执行模式（五档选择器）配置，仅影响 {@code PLAN} 档的 mutating 工具判定与
 * {@code MANUAL} 档的工具展示。见 {@code SandboxRiskDetector#isMutatingTool} /
 * {@code ExecutionModePolicy}。
 *
 * <p>只放"未来可能需要按环境微调"的判定项，不过度设计：命令执行类工具的名字关键字、
 * 以及 PLAN 档下强制只读/强制 mutating 的工具名正则白/黑名单。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.execution-mode")
public class AdminExecutionModeProperties {

    /** 命令执行类工具的名字关键字（不区分大小写子串匹配），命中即在 PLAN 档视为 mutating。 */
    public static final List<String> DEFAULT_EXEC_TOOL_KEYWORDS =
        List.of("exec", "run", "shell", "command", "bash", "terminal");

    /** 命令执行类工具名字关键字（默认见 {@link #DEFAULT_EXEC_TOOL_KEYWORDS}）。 */
    private List<String> execToolKeywords = new ArrayList<>(DEFAULT_EXEC_TOOL_KEYWORDS);

    /**
     * PLAN 档"强制视为只读"的工具名正则白名单（默认空）：命中的工具即便被启发式判成 mutating
     * 也放行执行。用于放行确定安全的工具（如某些只读的 exec 探针）。
     */
    private List<String> planReadonlyToolPatterns = new ArrayList<>();

    /**
     * PLAN 档"强制视为 mutating"的工具名正则黑名单（默认空）：命中的工具一律拦改，
     * 优先级高于白名单与启发式。用于兜住启发式识别不到的写副作用工具。
     */
    private List<String> planMutatingToolPatterns = new ArrayList<>();
}
