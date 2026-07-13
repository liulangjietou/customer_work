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

    private Docker docker = new Docker();

    private Guard guard = new Guard();

    public boolean isDockerMode() {
        return "docker".equalsIgnoreCase(mode);
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
}
