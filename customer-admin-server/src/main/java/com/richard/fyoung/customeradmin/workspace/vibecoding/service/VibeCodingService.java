package com.richard.fyoung.customeradmin.workspace.vibecoding.service;

import com.richard.fyoung.customeradmin.workspace.runtime.AgentWorkspaceManager;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.common.constant.AgentCapabilities;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminSandboxProperties;
import com.richard.fyoung.customeradmin.workspace.audit.AiCodingOperation;
import com.richard.fyoung.customeradmin.workspace.audit.entity.AiCodingAuditLog;
import com.richard.fyoung.customeradmin.workspace.audit.service.AiCodingAuditService;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatNodeKind;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatStreamChunk;
import com.richard.fyoung.customeradmin.workspace.callstats.service.AgentCallMetaFactory;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatService;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customeradmin.workspace.runtime.WorkspaceRuntimeScope;
import com.richard.fyoung.customerwork.data.calllog.AgentCallMeta;
import com.richard.fyoung.customerwork.data.calllog.AgentCallSessionType;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.FileChangeEvent;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.RollbackResult;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.TestReport;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
    /** 文件内容读取的最大字节数（4MB），超过此限制返回截断提示，防止超大文件撑爆内存。 */
    private static final long MAX_FILE_SIZE_BYTES = 4 * 1024 * 1024;
    /** {@link GitWorkspaceService} 在会话目录里现场建的 git 仓库目录名，产物文件树/快照均需跳过。 */
    /**
     * 失败自动修复的轮数上限（需求 P0-3 §4.3.2）：Agent 侧由 prompt 约束"最多重试 3 轮"，
     * 服务侧据此统计失败次数——第 3 次仍失败的 test_report 标注 {@code exhausted=true}，
     * 供前端明确提示"已达最大修复轮次"。
     */
    private static final int MAX_FIX_ROUNDS = 3;

    private final ChatService chatService;
    private final AdminAgentInstanceFactory agentInstanceFactory;
    private final AgentWorkspaceManager workspaceManager;
    private final AiAgentMapper agentMapper;
    private final GitWorkspaceService gitWorkspaceService;
    private final AdminSandboxProperties sandboxProperties;
    private final AiCodingAuditService auditService;
    private final PlanConfirmationService planConfirmationService;
    private final AgentCallMetaFactory agentCallMetaFactory;

    /** {@code agentCode:sessionId -> 对话开始前的目录快照}，进程内、重启丢失（v1 降级方案的一部分）。 */
    private final Map<String, Map<String, FileFingerprint>> beforeSnapshots = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VibeCodingService(ChatService chatService, AdminAgentInstanceFactory agentInstanceFactory,
                              AiAgentMapper agentMapper, GitWorkspaceService gitWorkspaceService,
                              AdminSandboxProperties sandboxProperties, AiCodingAuditService auditService,
                              PlanConfirmationService planConfirmationService,
                              AgentCallMetaFactory agentCallMetaFactory,
                                    AgentWorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
        this.chatService = chatService;
        this.agentInstanceFactory = agentInstanceFactory;
        this.agentMapper = agentMapper;
        this.gitWorkspaceService = gitWorkspaceService;
        this.sandboxProperties = sandboxProperties;
        this.auditService = auditService;
        this.planConfirmationService = planConfirmationService;
        this.agentCallMetaFactory = agentCallMetaFactory;
    }

    /** 当前 VibeCoding 沙箱模式（{@code admin.sandbox.mode} 全局配置，不随会话变化）："docker"｜"local"。 */
    public String sandboxMode() {
        if (sandboxProperties.isDisabledMode()) {
            return "disabled";
        }
        return sandboxProperties.isDockerMode() ? "docker" : "local";
    }

    /** 安全中断该会话正在执行的对话，薄委托——VibeCoding 与普通对话共用同一个 Agent 实例和会话状态，
     * 中断本身跟文件快照/审计无关，不需要额外逻辑。 */
    public boolean interrupt(String agentCode, String sessionId) {
        return chatService.interrupt(agentCode, sessionId);
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
        + "生成主代码后，请同时生成对应的 JUnit 5 / Mockito 单元测试，覆盖正常路径与异常路径。\n"
        + "生成或修改代码后，请在沙箱内执行编译或测试命令（如 javac、mvn test）验证代码能正确运行，"
        + "并将执行结果告知用户；命令已在隔离沙箱中执行，无需额外确认。\n"
        + "若编译或测试失败，请分析失败原因、修复代码后重新验证，最多重试 3 轮；3 轮后仍失败则停止重试，"
        + "并明确总结失败原因，不要无限重试。\n\n%s";

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
    /** 流式对话（未指定执行模式）：保留旧三参签名，供协作链路/既有测试使用，回落全局模式语义。 */
    public Flux<ChatStreamChunk> stream(String agentCode, String sessionId, String userText) {
        return stream(agentCode, sessionId, userText, null, null);
    }

    /** 流式对话（带执行模式，无附件）：保留旧四参签名，供既有调用点/测试使用。 */
    public Flux<ChatStreamChunk> stream(String agentCode, String sessionId, String userText, String mode) {
        return stream(agentCode, sessionId, userText, mode, null);
    }

    /**
     * 流式对话（带执行模式 + 附件绑定）：{@code attachmentIds} 透传给 {@link ChatService#chatStream}，
     * 在请求线程同步段把附件绑定到本条用户消息（框架 Msg.id）。VibeCoding 面板上传的附件走此链路。
     */
    public Flux<ChatStreamChunk> stream(String agentCode, String sessionId, String userText, String mode,
                                         List<String> attachmentIds) {
        if (sandboxProperties.isDisabledMode()) {
            return Flux.error(new IllegalStateException(
                "VibeCoding disabled: isolated sandbox runtime is not configured"));
        }
        requireVibeCodingCapable(agentCode);
        // 创建会话子目录（幂等），并以此为基础拍快照，对话结束后 diff 出本轮变更
        Path sessionWorkspace = workspaceManager.resolveSessionWorkspace(agentCode, sessionId);
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
        // 采集元数据同样在请求线程同步段构建：渠道=vibecoding → VIBE_CODING，question 用用户原始输入
        // （而非注入路径指引后的 enrichedText，报表展示的是用户真实提问）。
        AgentCallMeta callMeta = agentCallMetaFactory.build(agentCode, AgentCallSessionType.VIBE_CODING, userText);
        AtomicReference<ChatUsage> usageTotal = new AtomicReference<>();

        AtomicReference<Map<String, FileFingerprint>> lastSnapshot = new AtomicReference<>(initialSnapshot);
        // 失败自动修复循环的进度计数（本轮对话内累积）：testRun=第几次编译/测试执行，
        // failedRuns=其中失败次数，用于给 test_report 事件填 round/exhausted（需求 P0-3 §4.3.2）。
        AtomicInteger testRun = new AtomicInteger(0);
        AtomicInteger failedRuns = new AtomicInteger(0);
        // 执行模式随消息透传给 ChatService：模式登记 + Plan 确认通道（plan/plan_result 合并进流）均由
        // chatStream 统一承接（对话/VibeCoding/协作共用同一套闭环），本类只在其输出上叠加 file_change/
        // test_report 检测与审计，plan 事件作为普通 chunk 透传给前端。
        Flux<ChatStreamChunk> chatFlux = chatService.chatStream(agentCode, sessionId, enrichedText, mode, callMeta, attachmentIds, usageTotal::set)
            .concatMap(chunk -> chunk.kind() == ChatNodeKind.TOOL_RESULT
                // 顺序：原始工具结果 → 结构化 test_report（若可识别为编译/测试执行）→ 实时 file_change
                ? Flux.concat(Flux.just(chunk),
                    Flux.fromIterable(detectTestReport(chunk, testRun, failedRuns)),
                    Flux.fromIterable(detectFileChanges(sessionWorkspace, lastSnapshot)))
                : Flux.just(chunk));
        // 流结束兜底：再检测一次文件变更，防止最后一次写入恰好没有跟在任何 TOOL_RESULT 之后（如异步落盘）。
        // docker 模式产物经 bind mount（P1-3）实时落宿主机会话目录，与 local 一样直接读磁盘即可，无需事后搬运。
        // Plan Mode 挂起/确认（plan/plan_result）由 ChatService.chatStream 内部的会话通道承接并合并进流
        // （见上），plan 事件此处作为普通 chunk 顺流透传，不再由本类另开通道，避免同一 (agentCode, sessionId)
        // 双开通道相互顶替。本类只在末尾兜底再检测一次文件变更并落审计。
        return chatFlux.concatWith(Flux.defer(() ->
            Flux.fromIterable(detectFileChanges(sessionWorkspace, lastSnapshot))
        )).doFinally(signal -> {
            // 审计（需求 §5.2/§5.3）：变更文件 = 本轮初始快照 vs 最终快照（含删除），token 为本轮
            // 全部模型调用的汇总；ChatService 内部已把流错误兜底成正常完成，这里的非 COMPLETE
            // 信号主要是用户取消/连接断开，如实记录。
            auditService.applyUsage(audit, usageTotal.get());
            auditService.applyChangedFiles(audit, changedPaths(initialSnapshot, snapshot(sessionWorkspace)));
            auditService.finish(audit, signal == SignalType.ON_COMPLETE ? null : "VIBECODING_STREAM_" + signal.name());
            // 产出物落权威存储：工作区在系统临时目录，不保存的话 OS 一清理 / 容器一销毁本轮生成的代码就没了。
            // 放在 doFinally 而非 onComplete——用户中途取消时本轮已写出的文件同样要保住。
            workspaceManager.persistSessionWorkspace(agentCode, safeSession);
        });
    }

    /**
     * Plan Mode 计划确认/拒绝（需求 P1-1）：完成对应挂起项，流侧中间件据此恢复或取消该高风险操作。
     * 会话归属校验隐含在 {@link PlanConfirmationService#confirm} 按 {@code (agentCode, sessionId)} 定位通道里——
     * planId 不存在/已处理/超时/服务重启后失效均 fast fail（{@link ResultCode#PLAN_CONFIRM_NOT_FOUND}）。
     */
    public void confirmPlan(String agentCode, String sessionId, String planId, boolean approved, String note) {
        requireVibeCodingCapable(agentCode);
        String safeSession = StringUtils.hasText(sessionId) ? sessionId : "default";
        AiCodingAuditLog audit = auditService.begin(AiCodingOperation.PLAN_CONFIRM, agentCode, safeSession);
        boolean resolved = planConfirmationService.confirm(agentCode, safeSession, planId, approved);
        if (!resolved) {
            auditService.finish(audit, ResultCode.PLAN_CONFIRM_NOT_FOUND.name());
            throw new BizException(ResultCode.PLAN_CONFIRM_NOT_FOUND);
        }
        log.info("[workspace] plan confirm handled, agentCode={}, sessionId={}, planId={}, approved={}, note={}",
            agentCode, safeSession, planId, approved, StringUtils.hasText(note) ? note : "-");
        auditService.finish(audit, (String) null);
    }

    /**
     * 本轮对话变更文件清单（相对于会话 workspace 的路径）。
     * 对比 {@link #stream} 开始前的快照与当前快照，返回新增或修改的文件。
     */
    public List<String> listChangedArtifacts(String agentCode, String sessionId) {
        requireVibeCodingCapable(agentCode);
        Path sessionWorkspace = workspaceManager.resolveSessionWorkspace(agentCode, sessionId);
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
        Path sessionWorkspace = workspaceManager.resolveSessionWorkspace(agentCode, sessionId);
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
        Path sessionWorkspace = workspaceManager.resolveSessionWorkspace(agentCode, sessionId);
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

    /**
     * 会话一键回滚：把会话 workspace 恢复到对话前的 baseline 状态（新增文件删除、修改/删除的已跟踪文件
     * 还原），{@code .git} 保留，baseline 不被破坏（回滚后可继续对话建立新变更）。
     *
     * <p>local 与 docker 模式均支持：docker 模式产物经 bind mount（P1-3）实时落宿主机会话目录，
     * 会话 git 仓库（{@link GitWorkspaceService#ensureRepo}）也建在同一宿主机目录，回滚即对该目录
     * 执行 git 还原，语义与 local 一致。破坏性操作 + baseline 缺失校验的安全兜底下沉在
     * {@link GitWorkspaceService#rollbackToBaseline}。</p>
     */
    public RollbackResult rollback(String agentCode, String sessionId) {
        requireVibeCodingCapable(agentCode);
        String safeSession = StringUtils.hasText(sessionId) ? sessionId : "default";
        Path sessionWorkspace = workspaceManager.resolveSessionWorkspace(agentCode, safeSession);
        // 审计（需求 §5.2/§5.3）：回滚是同步操作，操作人取自 Sa-Token ThreadLocal，begin/finish 同线程；
        // 业务动作自持控制流，审计只在旁路记录（含 baseline 缺失/git 失败等破坏性尝试的失败留痕）。
        AiCodingAuditLog audit = auditService.begin(AiCodingOperation.ROLLBACK, agentCode, safeSession);
        try {
            RollbackResult result = gitWorkspaceService.rollbackToBaseline(sessionWorkspace);
            // 回滚后清空本轮内存快照，下次对话据当前（已回滚）状态重新拍基准，避免误报"变更"
            beforeSnapshots.remove(snapshotKey(agentCode, safeSession));
            List<String> affected = new ArrayList<>(result.restoredFiles());
            affected.addAll(result.deletedFiles());
            auditService.applyChangedFiles(audit, affected);
            log.info("[workspace] session rolled back, agentCode={}, sessionId={}, restored={}, deleted={}",
                agentCode, safeSession, result.restoredFiles().size(), result.deletedFiles().size());
            // 回滚同样是写操作：不保存的话权威副本仍是回滚前的状态，下次恢复会把已撤销的改动又拉回来
            workspaceManager.persistSessionWorkspace(agentCode, safeSession);
            auditService.finish(audit, (String) null);
            return result;
        } catch (RuntimeException e) {
            auditService.finish(audit, e);
            throw e;
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
            Path sessionWorkspace = workspaceManager.resolveSessionWorkspace(agentCode, sessionId);
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
            workspaceManager.persistSessionWorkspace(agentCode, sessionId);
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
            ? Arrays.asList(agent.getCapabilities().split(AgentCapabilities.DELIMITER)) : List.of();
        if (!capabilities.contains(AgentCapabilities.VIBECODING)) {
            throw new BizException(ResultCode.AGENT_CAPABILITY_NOT_SUPPORTED, "智能体未开启 vibecoding 能力: " + agentCode);
        }
    }

    private String snapshotKey(String agentCode, String sessionId) {
        return WorkspaceRuntimeScope.session(agentCode, sessionId);
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

    /**
     * 从一条 {@code TOOL_RESULT} 展示文本解析出结构化 {@code test_report} 事件（0 或 1 条）。
     *
     * <p>local 与 docker 模式均启用：解析对象是命令执行的 {@code TOOL_RESULT} 输出文本，两种模式下
     * 该文本都会随流回传；docker 模式产物又经 bind mount（<b>P1-3</b>）实时落宿主机，报告引用的文件
     * 与宿主机文件树一致，不再降级。</p>
     *
     * <p>失败驱动的自动修复由 Agent 侧 prompt 约束（最多 3 轮），本方法只按执行序累计轮次：
     * {@code round} 为本轮对话第几次编译/测试执行；累计失败达 {@link #MAX_FIX_ROUNDS} 且当前仍失败时
     * 标注 {@code exhausted=true}，让前端明确"已达最大修复轮次"。</p>
     */
    private List<ChatStreamChunk> detectTestReport(ChatStreamChunk toolResult, AtomicInteger testRun, AtomicInteger failedRuns) {
        Optional<TestReport> parsed = TestReportParser.parse(toolResult.text());
        if (parsed.isEmpty()) {
            return List.of();
        }
        TestReport base = parsed.get();
        int round = testRun.incrementAndGet();
        int fails = base.success() ? failedRuns.get() : failedRuns.incrementAndGet();
        boolean exhausted = !base.success() && fails >= MAX_FIX_ROUNDS;
        log.info("[workspace] sandbox test report parsed, command={}, success={}, round={}, exhausted={}",
            base.command(), base.success(), round, exhausted);
        return List.of(testReportChunk(base.withProgress(round, exhausted)));
    }

    private ChatStreamChunk testReportChunk(TestReport report) {
        try {
            return new ChatStreamChunk(ChatNodeKind.TEST_REPORT, objectMapper.writeValueAsString(report));
        } catch (JsonProcessingException e) {
            log.error("[workspace] test_report json serialize failed, code={}, command={}",
                "TEST_REPORT_JSON_ERROR", report.command(), e);
            return new ChatStreamChunk(ChatNodeKind.TEST_REPORT, "{}");
        }
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
                    return GitWorkspaceService.GIT_DIR_NAME.equals(dir.getFileName().toString())
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
            stream.filter(path -> !GitWorkspaceService.GIT_DIR_NAME.equals(path.getFileName().toString()))
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
