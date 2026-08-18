package com.richard.fyoung.customeradmin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * VibeCoding 沙箱执行参数：{@link #mode} 决定 {@code AdminAgentInstanceFactory} 给
 * {@code HarnessAgent} 挂载 {@code LocalFilesystemSpec} 还是 {@code DockerFilesystemSpec}——
 * 两者是同一套 {@code .filesystem(...)} 挂载点，切换不需要新增 Maven 依赖
 * （{@code agentscope-harness} 已经内置 Docker 沙箱实现）。
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.sandbox")
public class AdminSandboxProperties {

    /** 沙箱后端：{@code local}（默认，无隔离，直接跑在宿主机）｜{@code docker}（容器隔离）。 */
    private String mode = "local";

    /** 单次命令执行超时（秒），local/docker 两种模式共用。 */
    private int executeTimeoutSeconds = 60;

    /**
     * 高风险操作的权限模式（需求 P1-1）：{@code bypass}（默认，保持现状——高风险命令由
     * {@code SandboxGuardMiddleware} 静默改写兜底，不打断流）｜{@code hitl}（挂起等人工确认——
     * Agent 计划执行高风险操作时先 emit {@code plan} 事件挂起，用户确认后再执行，拒绝/超时则取消）。
     * 默认 {@code SandboxPermissionMode.BYPASS} 保证不影响既有非 vibecoding 链路与既有默认行为。
     */
    private SandboxPermissionMode permissionMode = SandboxPermissionMode.BYPASS;

    private Docker docker = new Docker();

    private Guard guard = new Guard();

    private Hitl hitl = new Hitl();

    /** AI 编码助手增量能力开关。全部默认关闭，按需灰度开启，不影响既有 VibeCoding 主链路。 */
    private Features features = new Features();

    public boolean isDockerMode() {
        return "docker".equalsIgnoreCase(mode);
    }

    public boolean isDisabledMode() {
        return "disabled".equalsIgnoreCase(mode);
    }

    /** 是否开启 Plan Mode 人工确认闭环（{@code admin.sandbox.permission-mode=hitl}）。 */
    public boolean isHitlMode() {
        return permissionMode == SandboxPermissionMode.HITL;
    }

    /**
     * 高风险操作权限模式。
     * <ul>
     *   <li>{@link #BYPASS}：现状——高风险命令由 {@code SandboxGuardMiddleware} 直接改写兜底，不挂起；</li>
     *   <li>{@link #HITL}：命中高风险操作先 emit {@code plan} 事件挂起等人工确认。</li>
     * </ul>
     */
    public enum SandboxPermissionMode {
        BYPASS,
        HITL
    }

    /** {@code mode=docker} 时生效的容器参数。 */
    @Data
    public static class Docker {
        /** 带 JDK+Maven 的镜像，贴合 Java 语言助手场景，生成代码后能直接编译/跑测试。 */
        private String image = "maven:3.9-eclipse-temurin-17";
        private long memoryMb = 512;
        private long cpuCount = 1;
        /** 容器网络模式，默认 {@code none}（不联网，降低沙箱逃逸/外联风险）。 */
        private String network = "none";
    }

    /** 破坏性命令拦截配置，见 {@code SandboxGuardMiddleware}。 */
    @Data
    public static class Guard {

        /** 破坏性入参命中后改写成的安全占位。 */
        public static final String DESTRUCTIVE_PLACEHOLDER = "[BLOCKED_BY_SANDBOX_GUARD]";

        /**
         * 破坏性字符串入参的默认拦截正则（不区分大小写）：覆盖 rm -rf、删 .git、
         * Windows del/format、访问 /etc、/root 等敏感宿主路径。
         */
        public static final List<String> DEFAULT_DESTRUCTIVE_PATTERNS = List.of(
            "rm\\s+-rf",
            "\\.git[/\\\\]",
            "del\\s+/[fs]",
            "format\\s",
            "(^|[\\s/])/etc(/|\\s|$)",
            "(^|[\\s/])/root(/|\\s|$)");

        private boolean enabled = true;
        private List<String> destructivePatterns = new ArrayList<>(DEFAULT_DESTRUCTIVE_PATTERNS);
    }

    /**
     * Plan Mode 人工确认（HITL）配置，仅在 {@link #permissionMode} = {@code hitl} 时生效，见
     * {@code PlanConfirmationMiddleware} / {@code SandboxRiskDetector}。
     */
    @Data
    public static class Hitl {

        /**
         * 需人工确认的"非只读/破坏性命令"默认正则（不区分大小写）——比 {@link Guard} 的"直接改写"清单更宽，
         * 覆盖需求 §4.4.2 的"执行非只读命令（mvn clean、rm 等）"。命中即挂起询问，而非静默改写。
         */
        public static final List<String> DEFAULT_CONFIRMABLE_COMMAND_PATTERNS = List.of(
            "\\brm\\b",
            "\\brmdir\\b",
            "\\bmv\\b",
            "mvn\\s+clean",
            "gradle\\s+clean",
            "git\\s+(reset|checkout|clean|revert|rebase)\\b");

        /**
         * "修改依赖版本"判定：写类工具的路径入参命中这些依赖/构建文件时视为高风险（需求 §4.4.2）。
         */
        public static final List<String> DEFAULT_DEPENDENCY_FILE_PATTERNS = List.of(
            "pom\\.xml",
            "build\\.gradle",
            "package\\.json",
            "build\\.gradle\\.kts");

        /** 人工确认等待超时（秒），默认 5 分钟；超时视为拒绝，流正常结束（需求 §4.4.2）。 */
        private int confirmTimeoutSeconds = 300;

        /** 单轮批量修改文件数阈值，超过即视为高风险需确认（需求 §4.4.2「单轮批量修改 > 3 个文件」）。 */
        private int batchModifyThreshold = 3;

        private List<String> confirmableCommandPatterns = new ArrayList<>(DEFAULT_CONFIRMABLE_COMMAND_PATTERNS);
        private List<String> dependencyFilePatterns = new ArrayList<>(DEFAULT_DEPENDENCY_FILE_PATTERNS);
    }

    /**
     * AI 编码助手 P1/P2 能力开关与会话沙箱回收参数。
     *
     * <p>命令执行、诊断、重构、沙箱管理均是新增攻击面，Java fallback 保持关闭；非生产环境由
     * {@code application.yml} 默认开放，生产环境由 prod profile 与启动门禁强制关闭。
     * {@code idleTimeoutMinutes} 只针对本模块管理的交互式命令沙箱，空闲超过阈值后在下一次查询/执行时回收。</p>
     */
    @Data
    public static class Features {
        private boolean commandExecutionEnabled = false;
        private boolean diagnosisEnabled = false;
        private boolean refactorEnabled = false;
        private boolean managementEnabled = false;
        private int idleTimeoutMinutes = 30;
    }
}
