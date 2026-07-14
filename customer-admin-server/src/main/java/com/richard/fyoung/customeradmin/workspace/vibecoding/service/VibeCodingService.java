package com.richard.fyoung.customeradmin.workspace.vibecoding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminSandboxProperties;
import com.richard.fyoung.customeradmin.workspace.audit.AiCodingOperation;
import com.richard.fyoung.customeradmin.workspace.audit.entity.AiCodingAuditLog;
import com.richard.fyoung.customeradmin.workspace.audit.service.AiCodingAuditService;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatNodeKind;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatStreamChunk;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatService;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.FileChangeEvent;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.WorkspaceFileContent;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.WorkspaceFileNode;
import io.agentscope.core.model.ChatUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * VibeCoding：对话（复用 ChatService）+ 会话级目录隔离（{@code sessions/{sessionId}/}）+ 产物清单。
 *
 * <h3>目录结构约定</h3>
 * <pre>
 * data/admin-workspace/{agentCode}/
 *   sessions/{sessionId}/   ← 每次对话的产出物目录（本类管理此层）
 *   MEMORY.md               ← 智能体长期记忆（由 HarnessAgent 管理）
 * </pre>
 *
 * <p>HarnessAgent workspace 根目录仍是 {@code {agentCode}/}（便于 MEMORY.md 等跨会话文件访问），
 * 本类在 stream 开始前创建 {@code sessions/{sessionId}/} 子目录，并通过系统提示词约定 Agent
 * 将产出物写入该子目录，实现不同会话产出物物理隔离。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class VibeCodingService {

    private static final Logger log = LoggerFactory.getLogger(VibeCodingService.class);
    private static final String CAPABILITY_VIBECODING = "vibecoding";
    private static final String CAPABILITY_DELIMITER = ",";
    /** 文件内容读取的最大字节数（4MB），超过此限制返回截断提示，防止超大文件撑爆内存。 */
    private static final long MAX_FILE_SIZE_BYTES = 4 * 1024 * 1024;
    /** {@link GitWorkspaceService} 在会话目录里现场建的 git 仓库目录名，产物文件树/快照均需跳过。 */
    private static final String GIT_DIR_NAME = ".git";
    /** docker 沙箱容器内 workspace 根（框架 DockerFilesystemSpec 默认值，系统提示实测为 /workspace）。 */
    private static final String CONTAINER_WORKSPACE_ROOT = "/workspace";
    /** 沙箱容器名前缀（框架 DockerSandbox 命名：agentscope-sandbox-{sandboxId}）。 */
    private static final String SANDBOX_CONTAINER_PREFIX = "agentscope-sandbox";
    /** 单条 docker cli 命令超时秒数。 */
    private static final long DOCKER_CMD_TIMEOUT_SECONDS = 20;
    /**
     * docker 可执行文件绝对路径：IDE/GUI 启动的 Java 进程不继承 shell 的 PATH（典型 macOS 坑，
     * 本项目 CLAUDE.local.md 已记录过同类问题），而 docker 常见是 Homebrew 装的、不在系统默认
     * PATH 里（不同于系统自带、默认 PATH 就有的 git）——裸写 "docker" 交给 PATH 解析在这种场景下
     * 会静默 “command not found”。启动时探测一次常见安装路径，找不到再退回裸命令名兜底。
     */
    private static final String DOCKER_EXECUTABLE = resolveDockerExecutable();

    private static String resolveDockerExecutable() {
        for (String candidate : new String[] {
            "/opt/homebrew/bin/docker",         // Apple Silicon Homebrew
            "/usr/local/bin/docker",             // Intel Homebrew / Docker Desktop 旧版
            "/Applications/Docker.app/Contents/Resources/bin/docker",
            "/usr/bin/docker"
        }) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return "docker"; // 兜底交给 PATH 解析，环境正常配置时依然可用
    }

    private final ChatService chatService;
    private final AdminAgentInstanceFactory agentInstanceFactory;
    private final AiAgentMapper agentMapper;
    private final GitWorkspaceService gitWorkspaceService;
    private final AdminSandboxProperties sandboxProperties;
    private final AiCodingAuditService auditService;

    /** {@code agentCode:sessionId -> 对话开始前的目录快照}，进程内、重启丢失（v1 降级方案的一部分）。 */
    private final Map<String, Map<String, FileFingerprint>> beforeSnapshots = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VibeCodingService(ChatService chatService, AdminAgentInstanceFactory agentInstanceFactory,
                              AiAgentMapper agentMapper, GitWorkspaceService gitWorkspaceService,
                              AdminSandboxProperties sandboxProperties, AiCodingAuditService auditService) {
        this.chatService = chatService;
        this.agentInstanceFactory = agentInstanceFactory;
        this.agentMapper = agentMapper;
        this.gitWorkspaceService = gitWorkspaceService;
        this.sandboxProperties = sandboxProperties;
        this.auditService = auditService;
    }

    /** 当前 VibeCoding 沙箱模式（{@code admin.sandbox.mode} 全局配置，不随会话变化）："docker"｜"local"。 */
    public String sandboxMode() {
        return sandboxProperties.isDockerMode() ? "docker" : "local";
    }

    /**
     * VibeCoding 中注入给 Agent 的文件操作路径指引模板。
     * 占位符 %s 依次为：沙箱模式标记（docker/local）、会话子目录相对路径 ×3、用户原始消息。
     * 首段模式标记随用户消息落库，历史对话据此显示 [VibeCoding指引-docker]/[VibeCoding指引-local]。
     */
    private static final String FILE_DIRECTIVE_TEMPLATE =
        "[VibeCoding指引-%s] 本次对话的会话目录为: sessions/%s/\n"
        + "请将本轮生成的所有文件都写入该目录下，使用 write_file 工具时路径必须以 sessions/%s/ 开头（如 sessions/%s/src/main/java/Foo.java）。\n"
        + "不要把代码只输出在对话消息里，必须调用 write_file 工具实际将文件写入到工作区。\n"
        + "生成或修改代码后，请在沙箱内执行编译或测试命令（如 javac、mvn test）验证代码能正确运行，"
        + "并将执行结果告知用户；命令已在隔离沙箱中执行，无需额外确认。\n\n%s";

    /**
     * 流式对话：校验能力 → 创建会话子目录 → 拍快照 → 注入路径指引 → 委托 ChatService 流式对话，
     * 并在每个工具返回结果后插入实时 {@code file_change} 事件。
     *
     * <p>会话子目录 {@code sessions/{sessionId}/} 在每次 stream 开始时创建（幂等）。
     * 同时在用户消息头部注入路径指引，强制要求 Agent 将产出物写入指定子目录。</p>
     *
     * <p><b>实时文件变更检测</b>：每当流中出现一个 {@link ChatNodeKind#TOOL_RESULT} 节点（代表某次
     * 工具调用刚刚执行完毕），就重新给会话目录拍一次快照，与"上一次已知快照"（初始为本轮对话开始前
     * 的快照）做差异对比，把新增/修改/删除的文件各自转成一条 {@link ChatNodeKind#FILE_CHANGE} 节点
     * 插入到该 TOOL_RESULT 之后。不依赖框架内部工具名（不管 Agent 用 write_file/edit_file 还是 shell
     * 命令改的文件都能感知到），也不需要额外起后台线程轮询——复用本就在流动的事件节奏，实测延迟就是
     * "工具刚跑完"那一刻，优于需求文档里"500ms 轮询"的兜底方案。流结束后再兜底检测一次，防止最后一次
     * 文件写入恰好没有跟在任何 TOOL_RESULT 之后的边界情况（如异步落盘）。</p>
     */
    public Flux<ChatStreamChunk> stream(String agentCode, String sessionId, String userText) {
        requireVibeCodingCapable(agentCode);
        // 创建会话子目录（幂等），并以此为基础拍快照，对话结束后 diff 出本轮变更
        Path sessionWorkspace = agentInstanceFactory.resolveSessionWorkspace(agentCode, sessionId);
        // 必须在本轮写入发生前建立 git 基线（幂等，仅会话第一轮真正生效）——如果拖到 Git 助手被
        // 点开时才建（GitAssistantService 内部同样调用 ensureRepo），本轮已经写完的文件会被基线
        // 提交一并吞掉，导致 diff 永远是空的。
        gitWorkspaceService.ensureRepo(sessionWorkspace);
        Map<String, FileFingerprint> initialSnapshot = snapshot(sessionWorkspace);
        beforeSnapshots.put(snapshotKey(agentCode, sessionId), initialSnapshot);
        // 在用户消息头部注入路径指引，确保 Agent 用 write_file 工具将代码写入 sessions/{sessionId}/ 子目录
        String safeSession = StringUtils.hasText(sessionId) ? sessionId : "default";
        String modeTag = sandboxMode();
        String enrichedText = String.format(FILE_DIRECTIVE_TEMPLATE, modeTag, safeSession, safeSession, safeSession, userText);

        // 审计条目必须在请求线程的同步段创建（操作人取自 Sa-Token 的 ThreadLocal），
        // doFinally 跑在 reactor 线程，届时已拿不到登录上下文。
        AiCodingAuditLog audit = auditService.begin(AiCodingOperation.CHAT_STREAM, agentCode, safeSession);
        AtomicReference<ChatUsage> usageTotal = new AtomicReference<>();

        AtomicReference<Map<String, FileFingerprint>> lastSnapshot = new AtomicReference<>(initialSnapshot);
        Flux<ChatStreamChunk> chatFlux = chatService.chatStream(agentCode, sessionId, enrichedText, usageTotal::set)
            .concatMap(chunk -> chunk.kind() == ChatNodeKind.TOOL_RESULT
                ? Flux.concat(Flux.just(chunk), Flux.fromIterable(detectFileChanges(sessionWorkspace, lastSnapshot)))
                : Flux.just(chunk));
        // 流结束兜底：docker 模式先把容器产物拷回宿主机会话目录（隔离期间容器碰不到宿主机，用完主动搬回），
        // 再检测一次文件变更——这样产物文件树/file_change/Git 助手在 docker 模式也能读到真实产物。
        return chatFlux.concatWith(Flux.defer(() -> {
            syncDockerArtifactsIfNeeded(agentCode, safeSession, sessionWorkspace);
            return Flux.fromIterable(detectFileChanges(sessionWorkspace, lastSnapshot));
        })).doFinally(signal -> {
            // 审计（需求 §5.2/§5.3）：变更文件 = 本轮初始快照 vs 最终快照（含删除），token 为本轮
            // 全部模型调用的汇总；ChatService 内部已把流错误兜底成正常完成，这里的非 COMPLETE
            // 信号主要是用户取消/连接断开，如实记录。
            auditService.applyUsage(audit, usageTotal.get());
            auditService.applyChangedFiles(audit, changedPaths(initialSnapshot, snapshot(sessionWorkspace)));
            auditService.finish(audit, signal == SignalType.ON_COMPLETE ? null : "VIBECODING_STREAM_" + signal.name());
        });
    }

    /**
     * 本轮对话变更文件清单（相对于会话 workspace 的路径）。
     * 对比 {@link #stream} 开始前的快照与当前快照，返回新增或修改的文件。
     */
    public List<String> listChangedArtifacts(String agentCode, String sessionId) {
        requireVibeCodingCapable(agentCode);
        Path sessionWorkspace = agentInstanceFactory.resolveSessionWorkspace(agentCode, sessionId);
        Map<String, FileFingerprint> before = beforeSnapshots.getOrDefault(snapshotKey(agentCode, sessionId), Map.of());
        Map<String, FileFingerprint> after = snapshot(sessionWorkspace);

        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, FileFingerprint> entry : after.entrySet()) {
            FileFingerprint beforeFingerprint = before.get(entry.getKey());
            if (!entry.getValue().equals(beforeFingerprint)) {
                changed.add(entry.getKey());
            }
        }
        return changed;
    }

    /**
     * 列出指定会话 workspace 下的文件目录树。
     * 目录优先、同级按名称字母序排列。
     *
     * @return 根节点列表（即 sessions/{sessionId}/ 下的直接子节点）
     */
    public List<WorkspaceFileNode> listWorkspaceFiles(String agentCode, String sessionId) {
        requireVibeCodingCapable(agentCode);
        Path sessionWorkspace = agentInstanceFactory.resolveSessionWorkspace(agentCode, sessionId);
        if (!Files.exists(sessionWorkspace)) {
            return List.of();
        }
        return buildFileTree(sessionWorkspace, sessionWorkspace);
    }

    /**
     * 读取指定会话 workspace 内某文件的内容。
     *
     * <p>安全校验：path 必须位于 sessions/{sessionId}/ 目录内，防止路径穿越（path traversal）。</p>
     *
     * @param agentCode 智能体编码
     * @param sessionId 会话 ID
     * @param relativePath 相对于 sessions/{sessionId}/ 的文件路径（如 {@code src/main/java/Foo.java}）
     * @return 文件内容 VO
     */
    public WorkspaceFileContent readFileContent(String agentCode, String sessionId, String relativePath) {
        requireVibeCodingCapable(agentCode);
        Path sessionWorkspace = agentInstanceFactory.resolveSessionWorkspace(agentCode, sessionId);
        // 路径穿越防御：normalize 后必须以 sessionWorkspace 开头
        Path filePath = sessionWorkspace.resolve(relativePath).normalize();
        if (!filePath.startsWith(sessionWorkspace.normalize())) {
            throw new BizException(ResultCode.PARAM_INVALID, "非法文件路径：不允许访问 workspace 目录以外的文件");
        }
        if (!Files.exists(filePath)) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "文件不存在: " + relativePath);
        }
        if (Files.isDirectory(filePath)) {
            throw new BizException(ResultCode.PARAM_INVALID, "指定路径是目录而非文件: " + relativePath);
        }
        try {
            long size = Files.size(filePath);
            if (size > MAX_FILE_SIZE_BYTES) {
                return new WorkspaceFileContent(relativePath, detectLanguage(relativePath),
                    "[文件过大（" + size / 1024 + " KB），无法在线预览，请下载后查看]", true);
            }
            String content;
            try {
                content = Files.readString(filePath, StandardCharsets.UTF_8);
            } catch (MalformedInputException e) {
                // 二进制文件
                content = "[二进制文件，无法在线预览]";
            }
            return new WorkspaceFileContent(relativePath, detectLanguage(relativePath), content, false);
        } catch (IOException e) {
            log.error("[workspace] read file content failed, agentCode={}, sessionId={}, path={}",
                agentCode, sessionId, relativePath, e);
            throw new BizException(ResultCode.SYSTEM_ERROR, "文件读取失败: " + relativePath);
        }
    }

    // ---------------------- private helpers ----------------------

    /**
     * 保存指定会话 workspace 内某文件的内容（区创建/覆盖写入）。
     *
     * <p>安全校验：path 必须位于 sessions/{sessionId}/ 目录内，防止路径穿越。</p>
     *
     * @param agentCode    智能体编码
     * @param sessionId    会话 ID
     * @param relativePath 相对于 sessions/{sessionId}/ 的文件路径
     * @param content      文件完整新内容
     */
    public void saveFileContent(String agentCode, String sessionId, String relativePath, String content) {
        requireVibeCodingCapable(agentCode);
        // 审计（需求 §5.2/§5.3）覆盖路径校验 + 写入全程：路径穿越等非法尝试同样留痕（result=0 +
        // 错误码）。业务动作自持控制流，审计只在旁路记录——审计服务不可用/被替换都不影响保存本身。
        AiCodingAuditLog audit = auditService.begin(AiCodingOperation.FILE_SAVE, agentCode, sessionId);
        auditService.applyChangedFiles(audit, List.of(relativePath));
        try {
            Path sessionWorkspace = agentInstanceFactory.resolveSessionWorkspace(agentCode, sessionId);
            // 路径穿越防御
            Path filePath = sessionWorkspace.resolve(relativePath).normalize();
            if (!filePath.startsWith(sessionWorkspace.normalize())) {
                throw new BizException(ResultCode.PARAM_INVALID, "非法文件路径：不允许写入 workspace 目录以外的文件");
            }
            if (Files.isDirectory(filePath)) {
                throw new BizException(ResultCode.PARAM_INVALID, "指定路径是目录，无法写入: " + relativePath);
            }
            try {
                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, content, StandardCharsets.UTF_8);
                log.info("[workspace] file saved by user, agentCode={}, sessionId={}, path={}", agentCode, sessionId, relativePath);
            } catch (IOException e) {
                log.error("[workspace] save file content failed, agentCode={}, sessionId={}, path={}", agentCode, sessionId, relativePath, e);
                throw new BizException(ResultCode.SYSTEM_ERROR, "文件保存失败: " + relativePath);
            }
            auditService.finish(audit, (String) null);
        } catch (RuntimeException e) {
            auditService.finish(audit, e);
            throw e;
        }
    }

    // ---------------------- private helpers ----------------------

    private void requireVibeCodingCapable(String agentCode) {
        AiAgent agent = agentMapper.selectOne(new LambdaQueryWrapper<AiAgent>().eq(AiAgent::getAgentCode, agentCode));
        if (agent == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "智能体不存在: " + agentCode);
        }
        List<String> capabilities = StringUtils.hasText(agent.getCapabilities())
            ? Arrays.asList(agent.getCapabilities().split(CAPABILITY_DELIMITER)) : List.of();
        if (!capabilities.contains(CAPABILITY_VIBECODING)) {
            throw new BizException(ResultCode.AGENT_CAPABILITY_NOT_SUPPORTED, "智能体未开启 vibecoding 能力: " + agentCode);
        }
    }

    private String snapshotKey(String agentCode, String sessionId) {
        return agentCode + ":" + (StringUtils.hasText(sessionId) ? sessionId : "default");
    }

    /**
     * docker 模式专用：对话结束后把容器内 {@code /workspace/sessions/{sessionId}/} 的产物
     * {@code docker cp} 回宿主机会话目录，让产物文件树/file_change/Git 助手能读到（容器隔离期间
     * 宿主机碰不到容器，只有此刻用完主动搬回，隔离性不受影响）。
     *
     * <p>不精确关联容器名——用我们注入的唯一 {@code sessionId} 在容器内的路径反查：哪个活着的
     * {@code agentscope-sandbox-*} 容器里存在 {@code sessions/{sessionId}} 目录，就从它拷。best-effort：
     * 容器已被回收 / docker 不可用 / 拷贝失败都只记日志，不打断主流程（local 模式直接跳过）。</p>
     */
    private void syncDockerArtifactsIfNeeded(String agentCode, String safeSession, Path sessionWorkspace) {
        if (!sandboxProperties.isDockerMode()) {
            return;
        }
        try {
            String containerPath = CONTAINER_WORKSPACE_ROOT + "/sessions/" + safeSession;
            for (String container : listSandboxContainers()) {
                if (!containerHasDir(container, containerPath)) {
                    continue;
                }
                Files.createDirectories(sessionWorkspace);
                // docker cp 容器:/workspace/sessions/{id}/. 宿主机会话目录/ —— 末尾 /. 表示拷目录内容而非目录本身
                boolean ok = runDocker("cp", container + ":" + containerPath + "/.", sessionWorkspace.toString());
                if (ok) {
                    log.info("[workspace] docker artifacts synced to host, agentCode={}, sessionId={}, container={}",
                        agentCode, safeSession, container);
                } else {
                    log.error("[workspace] docker cp failed, code={}, sessionId={}, container={}",
                        "DOCKER_ARTIFACT_CP_FAIL", safeSession, container);
                }
                return;
            }
            log.info("[workspace] no live sandbox container holds session, skip artifact sync, sessionId={}", safeSession);
        } catch (Exception e) {
            log.error("[workspace] docker artifact sync error, code={}, sessionId={}", "DOCKER_ARTIFACT_SYNC_ERROR", safeSession, e);
        }
    }

    /** 列出当前活着的沙箱容器名（{@code docker ps --filter name=agentscope-sandbox}）。 */
    private List<String> listSandboxContainers() {
        List<String> names = new ArrayList<>();
        try {
            Process p = new ProcessBuilder(DOCKER_EXECUTABLE, "ps",
                "--filter", "name=" + SANDBOX_CONTAINER_PREFIX, "--format", "{{.Names}}")
                .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(DOCKER_CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return names;
            }
            for (String line : out.split("\n")) {
                if (StringUtils.hasText(line)) {
                    names.add(line.trim());
                }
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("[workspace] docker ps failed, code={}", "DOCKER_PS_FAIL", e);
        }
        return names;
    }

    /** 容器内是否存在指定目录（{@code docker exec {c} test -d {path}} 退出码 0）。 */
    private boolean containerHasDir(String container, String path) {
        return runDocker("exec", container, "test", "-d", path);
    }

    /** 执行一条 docker 命令，退出码 0 返回 true；超时/异常返回 false，失败时把命令和输出打进日志方便排查。 */
    private boolean runDocker(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(DOCKER_EXECUTABLE);
        cmd.addAll(Arrays.asList(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(DOCKER_CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.error("[workspace] docker command timeout, code={}, cmd={}", "DOCKER_CMD_TIMEOUT", cmd);
                return false;
            }
            boolean success = p.exitValue() == 0;
            if (!success) {
                log.error("[workspace] docker command failed, code={}, cmd={}, exitValue={}, output={}",
                    "DOCKER_CMD_FAIL", cmd, p.exitValue(), output);
            }
            return success;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("[workspace] docker command exec error, code={}, cmd={}", "DOCKER_CMD_EXEC_ERROR", cmd, e);
            return false;
        }
    }

    /**
     * 对比 {@code lastSnapshotRef} 持有的快照与当前实时快照，把差异转成 {@link ChatNodeKind#FILE_CHANGE}
     * 节点列表，并把 {@code lastSnapshotRef} 原地推进到最新快照（下次调用只看"这次调用之后新发生"的变化，
     * 不会重复上报同一处变更）。目录不存在（尚无任何文件写入）时直接返回空列表。
     */
    private List<ChatStreamChunk> detectFileChanges(Path workspace, AtomicReference<Map<String, FileFingerprint>> lastSnapshotRef) {
        Map<String, FileFingerprint> before = lastSnapshotRef.get();
        Map<String, FileFingerprint> after = snapshot(workspace);
        if (before.equals(after)) {
            return List.of();
        }
        List<ChatStreamChunk> chunks = new ArrayList<>();
        for (Map.Entry<String, FileFingerprint> entry : after.entrySet()) {
            FileFingerprint previous = before.get(entry.getKey());
            if (previous == null) {
                chunks.add(fileChangeChunk(FileChangeEvent.OPERATION_CREATE, entry.getKey(), "新增文件"));
            } else if (!previous.equals(entry.getValue())) {
                chunks.add(fileChangeChunk(FileChangeEvent.OPERATION_MODIFY, entry.getKey(), "修改文件"));
            }
        }
        for (String path : before.keySet()) {
            if (!after.containsKey(path)) {
                chunks.add(fileChangeChunk(FileChangeEvent.OPERATION_DELETE, path, "删除文件"));
            }
        }
        lastSnapshotRef.set(after);
        return chunks;
    }

    /**
     * 两份快照间的全部变更文件路径（新增/修改/删除都算，与 file_change 事件口径一致），供审计
     * 记录本轮对话的变更清单。与 {@link #listChangedArtifacts} 的差别：那边是"产物清单"语义
     * （只含存在的文件，不含删除），两者语义不同不强行合并。
     */
    private List<String> changedPaths(Map<String, FileFingerprint> before, Map<String, FileFingerprint> after) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, FileFingerprint> entry : after.entrySet()) {
            if (!entry.getValue().equals(before.get(entry.getKey()))) {
                changed.add(entry.getKey());
            }
        }
        for (String path : before.keySet()) {
            if (!after.containsKey(path)) {
                changed.add(path);
            }
        }
        return changed;
    }

    private ChatStreamChunk fileChangeChunk(String operation, String path, String description) {
        try {
            String json = objectMapper.writeValueAsString(new FileChangeEvent(operation, path, description));
            return new ChatStreamChunk(ChatNodeKind.FILE_CHANGE, json);
        } catch (JsonProcessingException e) {
            log.error("[workspace] file_change json serialize failed, code={}, operation={}, path={}",
                "FILE_CHANGE_JSON_ERROR", operation, path, e);
            return new ChatStreamChunk(ChatNodeKind.FILE_CHANGE, "{}");
        }
    }

    /**
     * 递归遍历 workspace 目录，记录每个文件的相对路径 -> (大小, 最后修改时间) 指纹。
     * 跳过 {@code .git} 子树——{@link GitWorkspaceService#ensureRepo} 会在会话目录里现场建 git 仓库，
     * 不跳过的话 git 内部文件（hooks/objects 等）会污染产物文件清单和 file_change 时间线。
     */
    private Map<String, FileFingerprint> snapshot(Path workspace) {
        Map<String, FileFingerprint> fingerprints = new HashMap<>();
        if (!Files.exists(workspace)) {
            return fingerprints;
        }
        try {
            Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return GIT_DIR_NAME.equals(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String relativePath = workspace.relativize(file).toString();
                    fingerprints.put(relativePath, new FileFingerprint(attrs.size(), attrs.lastModifiedTime().toMillis()));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("[workspace] vibecoding snapshot failed, code={}, workspace={}", "VIBECODING_SNAPSHOT_ERROR", workspace, e);
        }
        return fingerprints;
    }

    /**
     * 递归构建文件目录树节点列表。目录优先、同级按名称字母序排列。
     *
     * @param root    会话 workspace 根目录（用于计算 relativePath）
     * @param current 当前递归目录
     */
    private List<WorkspaceFileNode> buildFileTree(Path root, Path current) {
        List<WorkspaceFileNode> nodes = new ArrayList<>();
        try (var stream = Files.list(current)) {
            stream.filter(path -> !GIT_DIR_NAME.equals(path.getFileName().toString()))
                .sorted(Comparator
                    .comparing((Path p) -> Files.isDirectory(p) ? 0 : 1)  // 目录优先
                    .thenComparing(p -> p.getFileName().toString()))
                .forEach(path -> {
                    String name = path.getFileName().toString();
                    String relativePath = root.relativize(path).toString();
                    boolean isDir = Files.isDirectory(path);
                    List<WorkspaceFileNode> children = isDir ? buildFileTree(root, path) : List.of();
                    nodes.add(new WorkspaceFileNode(name, relativePath, isDir, children));
                });
        } catch (IOException e) {
            log.error("[workspace] list workspace files failed, code={}, path={}", "WORKSPACE_LIST_ERROR", current, e);
        }
        return nodes;
    }

    /**
     * 根据文件扩展名推断编程语言（供前端代码高亮组件使用）。
     * 未能识别的扩展名返回 {@code "text"}。
     */
    private String detectLanguage(String fileName) {
        if (fileName == null) return "text";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return "text";
        return switch (fileName.substring(dot + 1).toLowerCase()) {
            case "java"       -> "java";
            case "kt"         -> "kotlin";
            case "groovy"     -> "groovy";
            case "xml", "pom" -> "xml";
            case "yaml", "yml"-> "yaml";
            case "json"       -> "json";
            case "properties" -> "properties";
            case "sql"        -> "sql";
            case "ts", "tsx"  -> "typescript";
            case "js", "jsx"  -> "javascript";
            case "vue"        -> "html";
            case "html", "htm"-> "html";
            case "css", "scss"-> "css";
            case "sh", "bash" -> "bash";
            case "py"         -> "python";
            case "go"         -> "go";
            case "rs"         -> "rust";
            case "c", "h"     -> "c";
            case "cpp", "hpp" -> "cpp";
            case "md"         -> "markdown";
            case "dockerfile" -> "dockerfile";
            default           -> "text";
        };
    }

    private record FileFingerprint(long size, long lastModifiedMillis) {
    }
}
