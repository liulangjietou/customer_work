package com.richard.fyoung.customeradmin.workspace.vibecoding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatStreamChunk;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatService;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.WorkspaceFileContent;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.WorkspaceFileNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

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

    private final ChatService chatService;
    private final AdminAgentInstanceFactory agentInstanceFactory;
    private final AiAgentMapper agentMapper;

    /** {@code agentCode:sessionId -> 对话开始前的目录快照}，进程内、重启丢失（v1 降级方案的一部分）。 */
    private final Map<String, Map<String, FileFingerprint>> beforeSnapshots = new ConcurrentHashMap<>();

    public VibeCodingService(ChatService chatService, AdminAgentInstanceFactory agentInstanceFactory,
                              AiAgentMapper agentMapper) {
        this.chatService = chatService;
        this.agentInstanceFactory = agentInstanceFactory;
        this.agentMapper = agentMapper;
    }

    /**
     * VibeCoding 中注入给 Agent 的文件操作路径指引模板。
     * 占位符 %s 依次为：会话子目录相对路径、相对路径、用户原始消息。
     */
    private static final String FILE_DIRECTIVE_TEMPLATE =
        "[VibeCoding 指引] 本次对话的会话目录为: sessions/%s/\n"
        + "请将本轮生成的所有文件都写入该目录下，使用 write_file 工具时路径必须以 sessions/%s/ 开头（如 sessions/%s/src/main/java/Foo.java）。\n"
        + "不要把代码只输出在对话消息里，必须调用 write_file 工具实际将文件写入到工作区。\n\n%s";

    /**
     * 流式对话：校验能力 → 创建会话子目录 → 拍快照 → 注入路径指引 → 委托 ChatService 流式对话。
     *
     * <p>会话子目录 {@code sessions/{sessionId}/} 在每次 stream 开始时创建（幂等）。
     * 同时在用户消息头部注入路径指引，强制要求 Agent 将产出物写入指定子目录。</p>
     */
    public Flux<ChatStreamChunk> stream(String agentCode, String sessionId, String userText) {
        requireVibeCodingCapable(agentCode);
        // 创建会话子目录（幂等），并以此为基础拍快照，对话结束后 diff 出本轮变更
        Path sessionWorkspace = agentInstanceFactory.resolveSessionWorkspace(agentCode, sessionId);
        beforeSnapshots.put(snapshotKey(agentCode, sessionId), snapshot(sessionWorkspace));
        // 在用户消息头部注入路径指引，确保 Agent 用 write_file 工具将代码写入 sessions/{sessionId}/ 子目录
        String safeSession = StringUtils.hasText(sessionId) ? sessionId : "default";
        String enrichedText = String.format(FILE_DIRECTIVE_TEMPLATE, safeSession, safeSession, safeSession, userText);
        return chatService.chatStream(agentCode, sessionId, enrichedText);
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

    /** 递归遍历 workspace 目录，记录每个文件的相对路径 -> (大小, 最后修改时间) 指纹。 */
    private Map<String, FileFingerprint> snapshot(Path workspace) {
        Map<String, FileFingerprint> fingerprints = new HashMap<>();
        if (!Files.exists(workspace)) {
            return fingerprints;
        }
        try {
            Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
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
            stream.sorted(Comparator
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
