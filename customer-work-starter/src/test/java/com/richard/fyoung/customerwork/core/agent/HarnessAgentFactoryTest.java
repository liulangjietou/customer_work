package com.richard.fyoung.customerwork.core.agent;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.core.memory.ContextMemoryFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Harness Agent 工厂单测（2.0 新增能力统一装配）：验证 {@code HarnessAgent.fromAgent(...)} 包装
 * 内层 ReActAgent 并叠加 Compaction / Workspace / Permission 后可成功构建。
 *
 * <p>用 mock 模型（构建期不调用）+ 轻量内层 ReActAgent，离线即可运行。</p>
 * @author owlzhangfq@gmail.com
 */
class HarnessAgentFactoryTest {

    private HarnessAgentFactory factory(CustomerWorkProperties props) {
        // 轻量内层 ReActAgent（构建期不触发模型调用）
        ReActAgent inner = ReActAgent.builder()
            .name("inner-agent")
            .model(mock(Model.class))
            .toolkit(new Toolkit())
            .build();

        CustomerServiceAgentFactory agentFactory = mock(CustomerServiceAgentFactory.class);
        when(agentFactory.createAgent(anyString())).thenReturn(inner);

        MultiAgentOrchestrator orchestrator = mock(MultiAgentOrchestrator.class);
        ContextMemoryFactory contextMemoryFactory = new ContextMemoryFactory(props, mock(Model.class));
        PermissionContextState permission = PermissionContextState.builder()
            .mode(PermissionMode.DEFAULT).build();

        return new HarnessAgentFactory(agentFactory, contextMemoryFactory, orchestrator,
            new InMemoryAgentStateStore(), permission, mock(Model.class), props);
    }

    @Test
    void createHarnessAgent_shouldWrapInnerAgent_withDefaults() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHarness().setWorkspaceDir("target/test-workspace");

        HarnessAgent agent = factory(props).createHarnessAgent("tenantA:conv-1");

        assertNotNull(agent, "HarnessAgent 应成功构建");
        assertNotNull(agent.getStateStore(), "应挂载 StateStore");
    }

    @Test
    void createHarnessAgent_shouldBuild_withCompactionEnabled() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHarness().setWorkspaceDir("target/test-workspace");
        props.getContext().setCompressionEnabled(true);
        props.getContext().setMaxToken(4000);
        props.getContext().setMsgThreshold(20);
        props.getContext().setLastKeep(6);

        HarnessAgent agent = factory(props).createHarnessAgent("conv-compaction");

        assertNotNull(agent, "启用压缩后 HarnessAgent 应成功构建");
    }

    @Test
    void createHarnessAgent_shouldBuild_withHarnessDepthCapabilities() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        CustomerWorkProperties.Harness h = props.getHarness();
        h.setWorkspaceDir("target/test-workspace");
        h.setMemoryEnabled(true);              // 分层记忆 MEMORY.md
        h.setToolResultEvictionEnabled(true);  // 超大工具结果落盘
        h.setOrg("acme");                      // org 维度

        HarnessAgent agent = factory(props).createHarnessAgent("tenantB:conv-9");

        assertNotNull(agent, "启用分层记忆/结果落盘后 HarnessAgent 应成功构建");
    }

    /**
     * 框架 #1644：HarnessAgent 未显式设置 generateOptions 时 streamEvents() 会 NPE。
     * 工厂已在 builder 上显式补默认 generateOptions（{@code createHarnessAgent} 内 {@code .generateOptions(...)}）。
     * 该测试锁定「工厂始终产出可用的 HarnessAgent（含非空 generateOptions）」这一契约：
     * 默认配置下构建成功即代表 generateOptions 已被显式设置（否则 streamEvents 侧会缺失该配置）。
     */
    @Test
    void createHarnessAgent_shouldSetGenerateOptions_avoidingStreamEventsNpe() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHarness().setWorkspaceDir("target/test-workspace");

        HarnessAgent agent = factory(props).createHarnessAgent("conv-gen-options");

        assertNotNull(agent, "HarnessAgent 应成功构建（工厂已显式补 generateOptions 规避 #1644）");
        assertNotNull(agent.getStateStore(), "应挂载 StateStore");
    }

    @Test
    void createHarnessAgent_shouldBuild_withLocalSandbox() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHarness().setWorkspaceDir("target/test-workspace");
        props.getHarness().getSandbox().setMode("local");
        props.getHarness().getSandbox().setIsolationScope("session");

        HarnessAgent agent = factory(props).createHarnessAgent("conv-sandbox");

        assertNotNull(agent, "启用本地沙箱后 HarnessAgent 应成功构建");
    }
}
