package com.richard.fyoung.customeradmin.workspace.runtime;

import com.richard.fyoung.customeradmin.workspace.memory.AgentMemoryScope;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.infra.config.RuntimeWorkDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 智能体工作区目录的解析、隔离与持久化。
 *
 * <p><b>为什么从 {@code AdminAgentInstanceFactory} 拆出来</b>：那个类有 33 个构造参数、25 个方法，
 * 混了 7 类职责，其中"在磁盘上定位/创建/回存工作区目录"与"把一个 Agent 装配出来"毫无关系——
 * 它甚至要 shell 调 {@code id} 命令取宿主机 uid/gid。这一簇有清晰的输入输出边界、
 * 只依赖 {@link SessionWorkspaceStorage} 一个协作者，是最先该切出来的一刀。</p>
 *
 * <p><b>路径穿越的全链路唯一防御点在这里</b>：{@link #resolveSessionWorkspace} 是 stream / files /
 * file-content / rollback 等所有会话级功能解析磁盘路径的公共入口，sessionId 由前端透传，
 * 校验只在此处做一次（fast-fail，符合"整条链路只需一处防御式编程"）。把它和装配逻辑混在一个
 * 900 行的类里，最大的风险是有人另起一条解析路径而绕过这道校验。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class AgentWorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(AgentWorkspaceManager.class);

    /** 工作区根：落系统临时目录，里面全是可重建的派生物（约定见 RuntimeWorkDir）。 */
    private static final String WORKSPACE_ROOT = RuntimeWorkDir.of("admin-workspace");

    private static final String SESSIONS_DIR_NAME = "sessions";

    /** 可信主体的 MEMORY.md 物理隔离子目录。 */
    private static final String SUBJECTS_DIR_NAME = "subjects";

    private static final long ID_CMD_TIMEOUT_SECONDS = 5;

    /**
     * 宿主机 JVM 进程的 {@code uid:gid}，供 {@code docker run --user} 使用；
     * 类加载时探测一次，进程生命周期内不会变。探测失败为 null，表示不注入。
     */
    private static final String HOST_UID_GID = resolveHostUidGid();

    private final SessionWorkspaceStorage sessionWorkspaceStorage;

    public AgentWorkspaceManager(ObjectProvider<SessionWorkspaceStorage> sessionWorkspaceStorageProvider) {
        // 容错 null provider：单测直传 null 构造本类以验证路径校验逻辑，无需拉起容器
        this.sessionWorkspaceStorage = sessionWorkspaceStorageProvider == null
            ? null : sessionWorkspaceStorageProvider.getIfAvailable();
    }

    /** 宿主机 uid:gid；探测失败返回 null（调用方据此决定不注入 {@code --user}）。 */
    public static String hostUidGid() {
        return HOST_UID_GID;
    }

    /**
     * bind mount 前置：预建宿主机 agent 工作区根及 {@code sessions/} 子目录并 fast fail。
     *
     * <p>若交给 {@code docker -v} 自动补建，缺失目录会以 root 属主创建，后续宿主机侧 git/回滚
     * 必然撞权限——这正是本方法要规避的场景，建不出来就不该继续挂载。
     * 返回绝对 normalize 路径，与 {@link #resolveSessionWorkspace} 同源（同一 {@code WORKSPACE_ROOT}
     * 相对 JVM 工作目录），保证容器内写入与宿主机读取指向同一目录。</p>
     *
     * <p>静态：只用静态常量、不碰实例状态，而调用方 {@code buildDockerFilesystemSpec} 刻意是静态包私有——
     * 让 {@code DockerSandboxIntegrationTest} 能直接消费生产装配而不必 mock 全套依赖。</p>
     */
    public static Path prepareHostWorkspaceRoot(String agentCode) {
        Path hostWorkspaceRoot = Path.of(WORKSPACE_ROOT, WorkspaceRuntimeScope.agent(agentCode))
            .toAbsolutePath().normalize();
        try {
            Files.createDirectories(hostWorkspaceRoot.resolve(SESSIONS_DIR_NAME));
        } catch (Exception e) {
            log.error("[workspace] create host workspace dir for bind mount failed, code={}, agentCode={}",
                "SANDBOX_BIND_MOUNT_INIT_ERROR", agentCode, e);
            throw new BizException(ResultCode.SYSTEM_ERROR, "docker 沙箱工作区目录创建失败: " + hostWorkspaceRoot);
        }
        return hostWorkspaceRoot;
    }

    /**
     * VibeCoding 沙箱工作区路径（智能体根目录）：{@code {临时根}/admin-workspace/{agentCode}}。
     * 仅供快照根路径使用；Agent 运行时请用 {@link #resolveSessionWorkspace(String, String)} 按会话隔离。
     */
    public Path resolveWorkspace(String agentCode) {
        return resolveWorkspace(AgentMemoryScope.current(agentCode));
    }

    /** 已冻结主体的工作区；可信请求下 MEMORY.md 物理隔离。 */
    public Path resolveWorkspace(AgentMemoryScope scope) {
        Path base = Path.of(WORKSPACE_ROOT, WorkspaceRuntimeScope.agent(scope.agentCode()));
        Path workspace = scope.trusted()
            ? base.resolve(SUBJECTS_DIR_NAME).resolve(scope.subjectHash()) : base;
        try {
            Files.createDirectories(workspace);
        } catch (Exception e) {
            log.error("[workspace] create workspace dir failed, code={}, agentCode={}",
                "WORKSPACE_INIT_ERROR", scope.agentCode(), e);
        }
        return workspace;
    }

    /**
     * VibeCoding 沙箱工作区路径（会话级隔离）：
     * {@code {临时根}/admin-workspace/{agentCode}/sessions/{sessionId}}。
     * HarnessAgent 的文件操作根目录，不同会话产出物物理隔离，互不污染。
     *
     * <p><b>安全约束（会话路径解析的全链路唯一防御点）</b>：本方法是 stream / files / file-content /
     * rollback 等所有会话级功能解析磁盘路径的公共入口，sessionId 由前端透传（正常值是 UUID v4），
     * 在此统一做路径穿越校验——含路径分隔符或 {@code ..} 的 sessionId 直接 fast fail，
     * 并对拼接结果 normalize 后强校验必须仍落在该 agent 的 {@code sessions/} 根目录内。
     * 否则恶意 sessionId（如 {@code ../其他会话ID} / 绝对路径）可越界访问他人会话甚至宿主机任意目录
     * （rollback 链路会对目标目录执行破坏性的 git checkout + clean）。</p>
     */
    public Path resolveSessionWorkspace(String agentCode, String sessionId) {
        String safeSession = requireSafeSessionId(sessionId);
        Path sessionsRoot = resolveWorkspace(AgentMemoryScope.current(agentCode))
            .resolve(SESSIONS_DIR_NAME).normalize();
        Path workspace = sessionsRoot.resolve(safeSession).normalize();
        // 双保险：字符黑名单之外，normalize 后必须仍是 sessions 根目录的真子路径（防未预见的编码绕过）
        if (!workspace.startsWith(sessionsRoot) || workspace.equals(sessionsRoot)) {
            log.error("[workspace] session workspace path traversal blocked, code={}, agentCode={}, sessionId={}",
                "SESSION_PATH_TRAVERSAL", agentCode, safeSession);
            throw new BizException(ResultCode.PARAM_INVALID, "非法 sessionId：解析路径越界");
        }
        try {
            Files.createDirectories(workspace);
        } catch (Exception e) {
            log.error("[workspace] create session workspace dir failed, code={}, agentCode={}, sessionId={}",
                "SESSION_WORKSPACE_INIT_ERROR", agentCode, safeSession, e);
        }
        // 恢复权威副本：工作区落在系统临时目录（会被 OS 清理）、容器化部署更是一销毁就没了，
        // 而会话产出物是用户生成的代码、不是可重建的派生物。本方法是所有会话级功能解析路径的公共入口，
        // 挂在这里能覆盖 stream / files / file-content / rollback 全部链路。
        // 仅在本地目录为空时真正拉取（见 SessionWorkspaceStorage#hydrate），故重复调用无额外开销。
        if (sessionWorkspaceStorage != null) {
            sessionWorkspaceStorage.hydrate(agentCode, safeSession, workspace);
        }
        return workspace;
    }

    /**
     * 把会话工作区保存回权威存储（对象存储）。产出物写入之后必须调用一次，
     * 否则本地临时目录一被清理就丢。
     *
     * <p>调用点覆盖三条会造成写入的链路：对话轮次结束、手工保存文件、一键回滚。
     * 未启用持久化时静默跳过；失败只记 error，不打断主链路（见 {@link SessionWorkspaceStorage#persist}）。</p>
     */
    public void persistSessionWorkspace(String agentCode, String sessionId) {
        if (sessionWorkspaceStorage == null) {
            return;
        }
        String safeSession = requireSafeSessionId(sessionId);
        sessionWorkspaceStorage.persist(agentCode, safeSession,
            resolveWorkspace(AgentMemoryScope.current(agentCode))
                .resolve(SESSIONS_DIR_NAME).resolve(safeSession).normalize());
    }

    /**
     * sessionId 合法性校验：空值回退 {@code default}；含 {@code /}、{@code \}、{@code ..} 任一直接拒绝。
     *
     * <p>前端生成的 sessionId 是 UUID v4（十六进制 + 连字符，见 customer-admin-web/src/utils/uuid.ts），
     * 正常值不可能出现这些字符，出现即恶意构造，fast fail。</p>
     */
    public static String requireSafeSessionId(String sessionId) {
        String safeSession = StringUtils.hasText(sessionId) ? sessionId : "default";
        if (safeSession.contains("/") || safeSession.contains("\\") || safeSession.contains("..")) {
            throw new BizException(ResultCode.PARAM_INVALID, "非法 sessionId：不允许包含路径分隔符或 ..");
        }
        return safeSession;
    }

    /**
     * 探测宿主机 JVM 进程的 {@code uid:gid}（POSIX {@code id -u} / {@code id -g}）；
     * 任何异常（命令缺失 / 超时 / 非 POSIX 平台）返回 null 表示不注入。
     */
    private static String resolveHostUidGid() {
        try {
            String uid = execIdCommand("-u");
            String gid = execIdCommand("-g");
            if (StringUtils.hasText(uid) && StringUtils.hasText(gid)) {
                return uid + ":" + gid;
            }
        } catch (Exception e) {
            log.error("[workspace] resolve host uid/gid failed, code={}", "SANDBOX_UID_RESOLVE_FAIL", e);
        }
        return null;
    }

    /** 执行 {@code id <flag>} 并返回 trim 后的输出；非零退出码 / 超时返回 null。 */
    private static String execIdCommand(String flag) throws Exception {
        Process process = new ProcessBuilder("id", flag).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!process.waitFor(ID_CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return null;
        }
        return process.exitValue() == 0 ? output : null;
    }
}
