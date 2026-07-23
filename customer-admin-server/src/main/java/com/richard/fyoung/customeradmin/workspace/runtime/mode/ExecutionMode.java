package com.richard.fyoung.customeradmin.workspace.runtime.mode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * 执行模式五档（对齐 Claude Code 的 Mode 交互），随每条消息下发、会话内生效。
 * 各档语义见 {@code ExecutionModePolicy}：
 * <ul>
 *   <li>{@link #AUTO}：默认——{@code SandboxRiskDetector.assess} 命中高风险才挂起确认，否则放行（即既有 HITL 行为）；</li>
 *   <li>{@link #MANUAL}：每个含工具调用的 acting 步都挂起确认；</li>
 *   <li>{@link #ACCEPT_EDITS}：同 AUTO，但把编辑类（BATCH_MODIFY/MODIFY_DEPENDENCY）视为自动放行；</li>
 *   <li>{@link #PLAN}：只读研究模式，mutating 工具不真正执行（入参改写为提示）；</li>
 *   <li>{@link #BYPASS}：纯透传（护栏 {@code SandboxGuardMiddleware} 仍是最后防线）。</li>
 * </ul>
 *
 * <p>序列化值为小写下划线（{@code auto/manual/accept_edits/plan/bypass}），与前端契约一致。
 * 请求未携带模式（旧调用方/存量测试）时由 {@code parse} 返回 {@code null} 表示"未指定"，
 * 交由调用方回落到全局 {@code AdminSandboxProperties.permissionMode} 语义。</p>
 * @author owlzhangfq@gmail.com
 */
public enum ExecutionMode {

    AUTO("auto"),
    MANUAL("manual"),
    ACCEPT_EDITS("accept_edits"),
    PLAN("plan"),
    BYPASS("bypass");

    private static final Logger log = LoggerFactory.getLogger(ExecutionMode.class);
    /** 非法模式值解析失败的错误码。 */
    private static final String CODE_INVALID_MODE = "EXECUTION-MODE-PARSE-INVALID";

    private final String value;

    ExecutionMode(String value) {
        this.value = value;
    }

    /** 序列化值（小写下划线），与前端 {@code mode} 字段契约一致。 */
    public String value() {
        return value;
    }

    /**
     * 防御式解析请求携带的模式串。
     * <ul>
     *   <li>{@code null}/空白 → 返回 {@code null}，表示"未指定"，由调用方走全局回落；</li>
     *   <li>非法值（非空但不匹配任一档）→ {@code log.error} 带错误码后同样返回 {@code null}
     *       （按"未指定"处理，<b>不</b>静默当成 AUTO，避免把"配错"与"没配"混为一谈）。</li>
     * </ul>
     * 匹配大小写不敏感，兼容序列化值（如 {@code accept_edits}）与枚举名（如 {@code ACCEPT_EDITS}）。
     */
    public static ExecutionMode parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String normalized = raw.trim();
        for (ExecutionMode mode : values()) {
            if (mode.value.equalsIgnoreCase(normalized) || mode.name().equalsIgnoreCase(normalized)) {
                return mode;
            }
        }
        log.error("[workspace] invalid execution mode value, code={}, raw={}", CODE_INVALID_MODE, raw);
        return null;
    }
}
