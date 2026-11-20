package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.core.constant.AgentFileNames;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.workspace.WorkspaceManager;

import java.nio.file.Path;

/**
 * Harness 分层记忆工作副本的位置：框架为「某个调用方」实际读写 {@code MEMORY.md} 的那个文件。
 *
 * <p>框架按隔离范围把运行期数据放在 {@code {workspace}/{namespace}/} 下（USER → userId、
 * SESSION → sessionId、AGENT / GLOBAL → workspace 根）。2.0.4 起写入就按 namespace 落盘，
 * 2.0.5 起读取也只认 namespace（不再回落 workspace 根）。同步服务若自己拼
 * {@code workspace/MEMORY.md}，水合的记忆框架读不到、框架沉淀的记忆也回写不到权威存储，
 * 且不报任何错——故路径一律交给框架的 {@link WorkspaceManager} 解析，本类不复刻命名规则。</p>
 *
 * <p>有实例时用 {@link #memoryFile(HarnessAgent, RuntimeContext)} 直接问框架；没有实例（如后台查看 /
 * 清空记忆）时用 {@link #memoryFile(Path, IsolationScope, RuntimeContext)}，调用方须传入构建实例时
 * 生效的隔离范围。两者一致性由 {@code HarnessMemoryFilesContractTest} 对真实构建的实例下断言。</p>
 * @author owlzhangfq@gmail.com
 */
public final class HarnessMemoryFiles {

    /**
     * 未挂 filesystem 时 {@code HarnessAgent.Builder#build} 采用的隔离范围（2.0.5 反编译：
     * 只有 Local / Sandbox / Remote 三种 FilesystemSpec 能改它）。框架改了默认值时契约测试会红。
     */
    public static final IsolationScope BUILDER_DEFAULT_ISOLATION = IsolationScope.USER;

    /** 框架分层记忆的原料目录名（与 {@code WorkspaceManager#getMemoryDir} 一致）。 */
    private static final String MEMORY_DIR_NAME = "memory";

    private HarnessMemoryFiles() {
    }

    /** 框架为该调用方读写的 MEMORY.md（按实例实际装配的隔离范围解析）。 */
    public static Path memoryFile(HarnessAgent agent, RuntimeContext rc) {
        return agent.getWorkspaceManager().resolveRuntimeDataPath(rc, AgentFileNames.MEMORY_MD);
    }

    /** 无实例时按工作区与隔离范围解析 MEMORY.md 位置；{@code isolation} 必须与构建实例时一致。 */
    public static Path memoryFile(Path workspace, IsolationScope isolation, RuntimeContext rc) {
        return locator(workspace, isolation).resolveRuntimeDataPath(rc, AgentFileNames.MEMORY_MD);
    }

    /** 无实例时按工作区与隔离范围解析记忆原料目录（按日 / 按会话沉淀的记忆文件）。 */
    public static Path memoryDir(Path workspace, IsolationScope isolation, RuntimeContext rc) {
        return locator(workspace, isolation).resolveRuntimeDataPath(rc, MEMORY_DIR_NAME);
    }

    /** 只用于路径解析的 WorkspaceManager：不挂 filesystem、不做 validate，构造无副作用。 */
    private static WorkspaceManager locator(Path workspace, IsolationScope isolation) {
        return new WorkspaceManager(workspace, null, null, isolation.toNamespaceFactory());
    }
}
