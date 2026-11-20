package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.core.agent.CustomerServiceAgentFactory;
import com.richard.fyoung.customerwork.core.agent.HarnessAgentFactory;
import com.richard.fyoung.customerwork.core.agent.MultiAgentOrchestrator;
import com.richard.fyoung.customerwork.core.constant.AgentFileNames;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Harness 分层记忆工作副本位置的契约：同步服务读写的文件必须就是框架为调用方读写的那个文件。
 *
 * <p>框架按隔离范围把 MEMORY.md 放在 {@code {workspace}/{namespace}/} 下：2.0.4 起写入如此，
 * 2.0.5 起读取也不再回落 workspace 根。同步服务此前自己拼 {@code workspace/MEMORY.md}，
 * 于是升级 2.0.5 后水合的记忆框架读不到（读到空串），而框架沉淀的记忆从 2.0.4 起就没回写过——
 * 两头都不报错。原有单测只在同步服务与磁盘之间往返，从不经过真实的 WorkspaceManager，照不出来。
 * 这里一律用真实构建的 HarnessAgent 断言。</p>
 * @author owlzhangfq@gmail.com
 */
class HarnessMemoryFilesContractTest {

    private static final String USER_ID = "tenantA";
    private static final String SESSION_ID = "tenantA:conv-1";

    @TempDir
    Path workspace;

    @Test
    void hydratedMemory_isVisibleToFramework_andFrameworkWritesArePersisted_withoutSandbox() throws Exception {
        assertRoundTrip("none");
    }

    @Test
    void hydratedMemory_isVisibleToFramework_andFrameworkWritesArePersisted_withLocalSandbox() throws Exception {
        assertRoundTrip("local");
    }

    @Test
    void locatorWithoutInstance_matchesInstanceResolution_forBuilderDefaultIsolation() {
        HarnessAgent agent = HarnessAgent.Builder.fromAgent(innerAgent())
            .stateStore(new InMemoryAgentStateStore())
            .workspace(workspace)
            .build();
        try {
            RuntimeContext rc = callerContext();
            assertEquals(HarnessMemoryFiles.memoryFile(agent, rc),
                HarnessMemoryFiles.memoryFile(workspace, HarnessMemoryFiles.BUILDER_DEFAULT_ISOLATION, rc),
                "未挂 filesystem 时框架的默认隔离范围变了：同步更新 BUILDER_DEFAULT_ISOLATION");
            assertEquals(agent.getWorkspaceManager().getMemoryDir(rc),
                HarnessMemoryFiles.memoryDir(workspace, HarnessMemoryFiles.BUILDER_DEFAULT_ISOLATION, rc));
            assertNotEquals(workspace.resolve(AgentFileNames.MEMORY_MD), HarnessMemoryFiles.memoryFile(agent, rc),
                "本用例的前提：带 userId 的调用方，记忆不在 workspace 根下");
        } finally {
            agent.close();
        }
    }

    @Test
    void locatorWithoutInstance_matchesInstanceResolution_forAgentIsolation() {
        HarnessAgent agent = HarnessAgent.Builder.fromAgent(innerAgent())
            .stateStore(new InMemoryAgentStateStore())
            .workspace(workspace)
            .filesystem(new LocalFilesystemSpec().isolationScope(IsolationScope.AGENT))
            .build();
        try {
            RuntimeContext rc = callerContext();
            assertEquals(HarnessMemoryFiles.memoryFile(agent, rc),
                HarnessMemoryFiles.memoryFile(workspace, IsolationScope.AGENT, rc));
        } finally {
            agent.close();
        }
    }

    private void assertRoundTrip(String sandboxMode) throws Exception {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHarness().setWorkspaceDir(workspace.toString());
        props.getHarness().setMemoryEnabled(true);
        props.getHarness().getSandbox().setMode(sandboxMode);
        InMemoryHarnessMemoryStore store = new InMemoryHarnessMemoryStore();
        String scopeId = workspace.toAbsolutePath().normalize().toString();
        store.save(scopeId, "# MEMORY\n- 用户偏好顺丰快递\n");
        HarnessAgentFactory factory = factory(props, store);

        HarnessAgent agent = factory.createHarnessAgent(SESSION_ID);
        try {
            WorkspaceManager wm = agent.getWorkspaceManager();
            RuntimeContext rc = callerContext();
            assertEquals("# MEMORY\n- 用户偏好顺丰快递\n", wm.readMemoryMd(rc),
                "水合的记忆必须落在框架为该调用方读取的位置（sandbox=" + sandboxMode + "）");

            wm.writeMemoryFileUtf8(rc, AgentFileNames.MEMORY_MD, "# MEMORY\n- 用户改用京东快递\n");
            factory.persistMemory(agent, SESSION_ID);
            assertEquals("# MEMORY\n- 用户改用京东快递\n", store.load(scopeId).orElse(null),
                "框架写入的记忆必须能回写到权威存储（sandbox=" + sandboxMode + "）");
            assertEquals("# MEMORY\n- 用户改用京东快递\n",
                Files.readString(HarnessMemoryFiles.memoryFile(agent, rc), StandardCharsets.UTF_8));
        } finally {
            agent.close();
        }
    }

    private HarnessAgentFactory factory(CustomerWorkProperties props, HarnessMemoryStore store) {
        CustomerServiceAgentFactory agentFactory = mock(CustomerServiceAgentFactory.class);
        when(agentFactory.createAgent(anyString())).thenReturn(innerAgent());
        when(agentFactory.contextFor(anyString())).thenReturn(callerContext());
        return new HarnessAgentFactory(agentFactory, new ContextMemoryFactory(props, mock(Model.class)),
            mock(MultiAgentOrchestrator.class), new InMemoryAgentStateStore(),
            PermissionContextState.builder().mode(PermissionMode.DEFAULT).build(), mock(Model.class), props,
            new HarnessMemorySyncService(store));
    }

    private static ReActAgent innerAgent() {
        return ReActAgent.builder().name("inner-agent").model(mock(Model.class)).toolkit(new Toolkit()).build();
    }

    /** 与客服链路 {@code AgentGovernanceAssembler#contextFor} 同形：userId 为租户解析结果，sessionId 为会话号。 */
    private static RuntimeContext callerContext() {
        return RuntimeContext.builder().userId(USER_ID).sessionId(SESSION_ID).build();
    }
}
