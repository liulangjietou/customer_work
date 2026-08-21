package com.richard.fyoung.customeradmin.workspace.runtime;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminSandboxProperties;
import com.richard.fyoung.customeradmin.workspace.audit.AiCodingOperation;
import com.richard.fyoung.customeradmin.workspace.audit.entity.AiCodingAuditLog;
import com.richard.fyoung.customeradmin.workspace.audit.service.AiCodingAuditService;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.CommandOutputEvent;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.CommandResultEvent;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.ManagedSandboxView;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.SandboxConfigView;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.TestReportParser;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P1-2/P2-3 会话级命令运行时：统一承担命令安全校验、local/docker 执行、实时输出、超时、审计与资源管理。
 *
 * <p>Docker 容器规格直接复用 {@link AdminAgentInstanceFactory#buildDockerFilesystemSpec}，因此镜像、CPU、
 * 内存、网络与 bind mount 和 Agent 主链路完全一致；唯一差异是命令输出由本服务直接读取
 * {@code docker exec} 进程，解决框架 {@code Sandbox#exec} 只能完成后一次性返回的问题。</p>
 */
@Service
public class SandboxCommandService {

    private static final Logger log = LoggerFactory.getLogger(SandboxCommandService.class);
    private static final String EVENT_OUTPUT = "command_output";
    private static final String EVENT_TEST_REPORT = "test_report";
    private static final String EVENT_RESULT = "command_result";
    private static final String OUTPUT_STREAM = "combined";
    private static final int EXIT_CODE_TIMEOUT = 124;
    private static final int OUTPUT_CHUNK_CHARS = 2048;
    private static final int MAX_REPORT_OUTPUT_CHARS = 512 * 1024;
    private static final int MAX_STREAM_OUTPUT_CHARS = 5 * 1024 * 1024;

    private final AdminSandboxProperties properties;
    private final SandboxRiskDetector riskDetector;
    private final AdminAgentInstanceFactory agentInstanceFactory;
    private final AiCodingAuditService auditService;
    private final Map<SandboxKey, ManagedSandbox> sandboxes = new ConcurrentHashMap<>();
    private final ExecutorService commandExecutor = Executors.newCachedThreadPool(daemonFactory("sandbox-command-"));
    private final ScheduledExecutorService timeoutExecutor =
        Executors.newSingleThreadScheduledExecutor(daemonFactory("sandbox-timeout-"));

    public SandboxCommandService(AdminSandboxProperties properties, SandboxRiskDetector riskDetector,
                                 AdminAgentInstanceFactory agentInstanceFactory, AiCodingAuditService auditService) {
        this.properties = properties;
        this.riskDetector = riskDetector;
        this.agentInstanceFactory = agentInstanceFactory;
        this.auditService = auditService;
    }

    /** 执行命令并返回实时事件流；同一用户、智能体、会话同一时刻只允许一个命令。 */
    public Flux<SandboxCommandEvent> execute(String agentCode, String sessionId, long userId, String command) {
        requireFeature(properties.getFeatures().isCommandExecutionEnabled());
        if (!StringUtils.hasText(command)) {
            throw new BizException(ResultCode.PARAM_INVALID, "命令不能为空");
        }
        String safeSession = AdminAgentInstanceFactory.requireSafeSessionId(sessionId);
        AiCodingAuditLog audit = auditService.begin(AiCodingOperation.COMMAND_EXECUTE, agentCode, safeSession);
        try {
            if (properties.getGuard().isEnabled() && riskDetector.matchesDestructive(command)) {
                throw new BizException(ResultCode.SANDBOX_COMMAND_BLOCKED);
            }
            if (properties.isDisabledMode()) {
                throw new BizException(ResultCode.SANDBOX_RUNTIME_FAILED, "沙箱运行时已禁用");
            }
            cleanupExpired();

            String tenantId = currentTenant();
            SandboxKey key = new SandboxKey(tenantId, userId, agentCode, safeSession);
            Path workspace = agentInstanceFactory.resolveSessionWorkspace(agentCode, safeSession);
            ManagedSandbox managed = sandboxes.computeIfAbsent(key,
                ignored -> new ManagedSandbox(key, properties.isDockerMode() ? "docker" : "local", workspace));
            if (!managed.inUse.compareAndSet(false, true)) {
                throw new BizException(ResultCode.SANDBOX_COMMAND_RUNNING);
            }
            managed.command = command;
            managed.status = SandboxStatus.STARTING;
            managed.cancelRequested.set(false);

            return Flux.create(sink -> {
                sink.onCancel(() -> cancelProcess(managed));
                commandExecutor.execute(() -> TenantContext.runWith(tenantId,
                    () -> runCommand(managed, command, audit, sink)));
            }, FluxSink.OverflowStrategy.BUFFER);
        } catch (RuntimeException e) {
            auditService.finish(audit, e);
            throw e;
        }
    }

    /** 当前用户在指定智能体下的会话沙箱列表。 */
    public List<ManagedSandboxView> list(String agentCode, long userId) {
        requireFeature(properties.getFeatures().isManagementEnabled());
        cleanupExpired();
        String tenantId = currentTenant();
        return sandboxes.values().stream()
            .filter(s -> TenantContext.sameTenant(s.key.tenantId, tenantId)
                && s.key.userId == userId
                && s.key.agentCode.equals(agentCode))
            .sorted(Comparator.comparing((ManagedSandbox s) -> s.createdAt).reversed())
            .map(this::toView)
            .toList();
    }

    /** 手动停止并删除一个会话沙箱；不存在时幂等返回 false。 */
    public boolean cleanup(String agentCode, String sessionId, long userId) {
        requireFeature(properties.getFeatures().isManagementEnabled());
        String safeSession = AdminAgentInstanceFactory.requireSafeSessionId(sessionId);
        SandboxKey key = new SandboxKey(currentTenant(), userId, agentCode, safeSession);
        ManagedSandbox managed = sandboxes.remove(key);
        if (managed == null) {
            return false;
        }
        AiCodingAuditLog audit = auditService.begin(AiCodingOperation.SANDBOX_CLEANUP, agentCode, safeSession);
        try {
            closeManaged(managed);
            auditService.finish(audit, (String) null);
            return true;
        } catch (RuntimeException e) {
            auditService.finish(audit, e);
            throw e;
        }
    }

    /** 全部生效配置的只读视图；不返回规则正文，避免前端据此探测绕过边界。 */
    public SandboxConfigView config() {
        AdminSandboxProperties.Docker docker = properties.getDocker();
        AdminSandboxProperties.Features features = properties.getFeatures();
        return new SandboxConfigView(properties.getMode(), properties.getExecuteTimeoutSeconds(),
            properties.getPermissionMode().name(),
            new SandboxConfigView.DockerConfig(
                docker.getImage(), docker.getMemoryMb(), docker.getCpuCount(), docker.getNetwork()),
            new SandboxConfigView.GuardConfig(properties.getGuard().isEnabled()),
            new SandboxConfigView.FeatureConfig(
                features.isCommandExecutionEnabled(), features.isDiagnosisEnabled(),
                features.isRefactorEnabled(), features.isManagementEnabled(), features.getIdleTimeoutMinutes()));
    }

    private void runCommand(ManagedSandbox managed, String command, AiCodingAuditLog audit,
                            FluxSink<SandboxCommandEvent> sink) {
        long startedAt = System.currentTimeMillis();
        StringBuilder reportOutput = new StringBuilder();
        AtomicBoolean timedOut = new AtomicBoolean(false);
        int exitCode = -1;
        try {
            if (managed.cancelRequested.get()) {
                auditService.finish(audit, "COMMAND_CANCELLED");
                sink.complete();
                return;
            }
            List<String> processCommand = managed.mode.equals("docker")
                ? dockerCommand(managed, command)
                : localCommand(command);
            ProcessBuilder builder = new ProcessBuilder(processCommand).redirectErrorStream(true);
            if (managed.mode.equals("local")) {
                builder.directory(managed.workspace.toFile());
            }
            Process process = builder.start();
            managed.process = process;
            if (managed.cancelRequested.get()) {
                process.destroyForcibly();
            }
            managed.status = SandboxStatus.RUNNING;
            var timeoutTask = timeoutExecutor.schedule(() -> {
                if (process.isAlive()) {
                    timedOut.set(true);
                    process.destroyForcibly();
                }
            }, properties.getExecuteTimeoutSeconds(), TimeUnit.SECONDS);

            int streamedChars = 0;
            boolean truncationNoticeSent = false;
            try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[OUTPUT_CHUNK_CHARS];
                int count;
                while ((count = reader.read(buffer)) >= 0) {
                    if (count == 0) {
                        continue;
                    }
                    String text = new String(buffer, 0, count);
                    appendTail(reportOutput, text);
                    if (streamedChars < MAX_STREAM_OUTPUT_CHARS) {
                        sink.next(new SandboxCommandEvent(EVENT_OUTPUT,
                            new CommandOutputEvent(OUTPUT_STREAM, text, System.currentTimeMillis())));
                        streamedChars += text.length();
                    } else if (!truncationNoticeSent) {
                        truncationNoticeSent = true;
                        sink.next(new SandboxCommandEvent(EVENT_OUTPUT,
                            new CommandOutputEvent(OUTPUT_STREAM,
                                "\n[Output truncated after 5 MiB]\n", System.currentTimeMillis())));
                    }
                }
            }
            process.waitFor();
            timeoutTask.cancel(false);
            exitCode = timedOut.get() ? EXIT_CODE_TIMEOUT : process.exitValue();
            long durationMs = System.currentTimeMillis() - startedAt;
            if (timedOut.get()) {
                String notice = "\nCommand timed out after " + properties.getExecuteTimeoutSeconds() + " seconds.\n";
                appendTail(reportOutput, notice);
                sink.next(new SandboxCommandEvent(EVENT_OUTPUT,
                    new CommandOutputEvent(OUTPUT_STREAM, notice, System.currentTimeMillis())));
            }
            TestReportParser.parseCommand(command, reportOutput.toString(), exitCode, durationMs)
                .ifPresent(report -> sink.next(new SandboxCommandEvent(EVENT_TEST_REPORT, report)));
            String containerId = managed.containerId;
            sink.next(new SandboxCommandEvent(EVENT_RESULT,
                new CommandResultEvent(exitCode, exitCode == 0, durationMs, timedOut.get(), containerId)));
            auditService.finish(audit, exitCode == 0 ? null : (timedOut.get() ? "COMMAND_TIMEOUT" : "COMMAND_EXIT_" + exitCode));
            sink.complete();
        } catch (Exception e) {
            managed.status = SandboxStatus.FAILED;
            log.error("interactive sandbox command failed, code={}, agentCode={}, sessionId={}",
                "SANDBOX-COMMAND-EXECUTE-FAIL", managed.key.agentCode, managed.key.sessionId, e);
            auditService.finish(audit, e);
            sink.error(new BizException(ResultCode.SANDBOX_RUNTIME_FAILED));
            if (managed.mode.equals("docker") && managed.sandbox == null) {
                sandboxes.remove(managed.key, managed);
            }
        } finally {
            managed.process = null;
            managed.command = null;
            managed.lastActiveAt = Instant.now();
            managed.inUse.set(false);
            if (managed.status != SandboxStatus.FAILED) {
                managed.status = SandboxStatus.IDLE;
            }
            agentInstanceFactory.persistSessionWorkspace(managed.key.agentCode, managed.key.sessionId);
        }
    }

    private List<String> localCommand(String command) {
        return List.of("sh", "-c", command);
    }

    private List<String> dockerCommand(ManagedSandbox managed, String command) throws Exception {
        ensureDockerSandbox(managed);
        return List.of(dockerExecutable(), "exec", "-w",
            AdminAgentInstanceFactory.CONTAINER_WORKSPACE_ROOT + "/sessions/" + managed.key.sessionId,
            managed.containerId, "sh", "-c", command);
    }

    private void ensureDockerSandbox(ManagedSandbox managed) throws Exception {
        if (managed.sandbox != null && managed.sandbox.isRunning()) {
            return;
        }
        SandboxFilesystemSpec spec = AdminAgentInstanceFactory.buildDockerFilesystemSpec(
            properties, managed.key.agentCode);
        SandboxContext context = spec.toSandboxContext();
        DockerSandboxClient client = (DockerSandboxClient) context.getClient();
        Sandbox sandbox = client.create(context.getWorkspaceSpec(), context.getSnapshotSpec(),
            (DockerSandboxClientOptions) context.getClientOptions());
        try {
            sandbox.start();
            DockerSandboxState state = (DockerSandboxState) sandbox.getState();
            managed.sandbox = sandbox;
            managed.containerId = state.getContainerId();
        } catch (Exception e) {
            closeQuietly(sandbox, managed.key);
            throw e;
        }
    }

    private ManagedSandboxView toView(ManagedSandbox managed) {
        String[] usage = managed.mode.equals("docker") && StringUtils.hasText(managed.containerId)
            ? dockerUsage(managed.containerId) : new String[] {null, null};
        Long memoryLimit = managed.mode.equals("docker") ? properties.getDocker().getMemoryMb() : null;
        Long cpuLimit = managed.mode.equals("docker") ? properties.getDocker().getCpuCount() : null;
        return new ManagedSandboxView(managed.key.sessionId, managed.mode, managed.containerId,
            managed.status.name(), managed.createdAt, managed.lastActiveAt, managed.command,
            usage[0], usage[1], memoryLimit, cpuLimit);
    }

    private String[] dockerUsage(String containerId) {
        Process process = null;
        try {
            process = new ProcessBuilder(dockerExecutable(), "stats", "--no-stream", "--format",
                "{{.CPUPerc}}|{{.MemUsage}}", containerId).redirectErrorStream(true).start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new String[] {null, null};
            }
            if (process.exitValue() != 0) {
                return new String[] {null, null};
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String[] parts = output.split("\\|", 2);
            return parts.length == 2 ? parts : new String[] {null, null};
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            log.error("read docker sandbox usage failed, code={}, containerId={}",
                "SANDBOX-DOCKER-STATS-FAIL", containerId, e);
            return new String[] {null, null};
        }
    }

    private void cleanupExpired() {
        int idleMinutes = Math.max(1, properties.getFeatures().getIdleTimeoutMinutes());
        Instant deadline = Instant.now().minus(Duration.ofMinutes(idleMinutes));
        for (ManagedSandbox managed : new ArrayList<>(sandboxes.values())) {
            if (!managed.inUse.get() && managed.lastActiveAt.isBefore(deadline)
                    && sandboxes.remove(managed.key, managed)) {
                closeManaged(managed);
            }
        }
    }

    private void cancelProcess(ManagedSandbox managed) {
        managed.cancelRequested.set(true);
        Process process = managed.process;
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private void closeManaged(ManagedSandbox managed) {
        managed.status = SandboxStatus.STOPPING;
        cancelProcess(managed);
        closeQuietly(managed.sandbox, managed.key);
        managed.sandbox = null;
        managed.containerId = null;
        managed.status = SandboxStatus.STOPPED;
        log.info("managed sandbox cleaned, agentCode={}, sessionId={}, mode={}",
            managed.key.agentCode, managed.key.sessionId, managed.mode);
    }

    private void closeQuietly(Sandbox sandbox, SandboxKey key) {
        if (sandbox == null) {
            return;
        }
        try {
            sandbox.close();
        } catch (Exception e) {
            log.error("close managed sandbox failed, code={}, agentCode={}, sessionId={}",
                "SANDBOX-CLOSE-FAIL", key.agentCode, key.sessionId, e);
        }
    }

    private void appendTail(StringBuilder output, String text) {
        output.append(text);
        int overflow = output.length() - MAX_REPORT_OUTPUT_CHARS;
        if (overflow > 0) {
            output.delete(0, overflow);
        }
    }

    private void requireFeature(boolean enabled) {
        if (!enabled) {
            throw new BizException(ResultCode.AI_CODING_FEATURE_DISABLED);
        }
    }

    private String currentTenant() {
        return TenantContext.isPresent() ? TenantContext.get() : TenantContext.DEFAULT;
    }

    private String dockerExecutable() {
        for (String candidate : List.of(
                "/opt/homebrew/bin/docker", "/usr/local/bin/docker",
                "/Applications/Docker.app/Contents/Resources/bin/docker", "/usr/bin/docker")) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return "docker";
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @PreDestroy
    public void shutdown() {
        sandboxes.values().forEach(this::closeManaged);
        sandboxes.clear();
        commandExecutor.shutdownNow();
        timeoutExecutor.shutdownNow();
    }

    private record SandboxKey(String tenantId, long userId, String agentCode, String sessionId) {
        private SandboxKey {
            Objects.requireNonNull(tenantId);
            Objects.requireNonNull(agentCode);
            Objects.requireNonNull(sessionId);
        }
    }

    private enum SandboxStatus {
        STARTING,
        RUNNING,
        IDLE,
        FAILED,
        STOPPING,
        STOPPED
    }

    private static final class ManagedSandbox {
        private final SandboxKey key;
        private final String mode;
        private final Path workspace;
        private final Instant createdAt = Instant.now();
        private final AtomicBoolean inUse = new AtomicBoolean();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private volatile Instant lastActiveAt = createdAt;
        private volatile SandboxStatus status = SandboxStatus.STARTING;
        private volatile Sandbox sandbox;
        private volatile String containerId;
        private volatile Process process;
        private volatile String command;

        private ManagedSandbox(SandboxKey key, String mode, Path workspace) {
            this.key = key;
            this.mode = mode;
            this.workspace = workspace;
        }
    }
}
