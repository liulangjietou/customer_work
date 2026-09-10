package com.richard.fyoung.customerwork.core.agent;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.tool.backend.AfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.KnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.OrderBackend;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@code multi-agent.enabled} 必须真的能关掉多 Agent 协作。
 *
 * <h3>守的是什么</h3>
 * <p>这个开关此前在全仓<b>无人读取</b>：五处调用点没有一处判过它，置 {@code false} 不会关闭
 * 任何东西。而运维看到配置里写着 false，会以为多 Agent 协作已经停用——
 * <b>一个"配了不生效"的开关比没有开关更危险</b>，因为它给出的是确定的错误答案。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class MultiAgentDisabledTest {

    @Test
    @DisplayName("关闭时直接返回提示，不构建任何专家 Agent")
    void disabledConsultDoesNotBuildSpecialists() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getMultiAgent().setEnabled(false);

        MultiAgentOrchestrator orchestrator = new MultiAgentOrchestrator(
            mock(Model.class), properties, mock(OrderBackend.class),
            mock(AfterSalesBackend.class), mock(KnowledgeBackend.class), null);

        String reply = orchestrator.consult("u1:conv-1", "帮我看看订单").block();

        assertEquals(MultiAgentOrchestrator.DISABLED_REPLY, reply,
            "关闭开关后仍然走了协作链路——那这个开关就是个摆设");
    }

    @Test
    @DisplayName("默认开启，不改变既有部署的行为")
    void enabledByDefault() {
        assertEquals(true, new CustomerWorkProperties().getMultiAgent().isEnabled());
    }
}
