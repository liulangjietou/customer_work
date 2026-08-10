package com.richard.fyoung.customerwork.infra.config;

import com.richard.fyoung.customerwork.core.service.CustomerServiceService;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RuntimeConfigApplier} 单测：成功提交（swap 新链 + 回写配置 + 覆盖提示词 + flush）与
 * 「全有或全无」——构建/校验失败绝不覆盖旧配置、不 swap、不 flush。
 * @author owlzhangfq@gmail.com
 */
class RuntimeConfigApplierTest {

    private CustomerWorkRuntimeConfig sampleDto() {
        CustomerWorkRuntimeConfig dto = new CustomerWorkRuntimeConfig();
        dto.getModel().setProvider("openai");
        dto.getModel().setName("gpt-4o");
        dto.getModel().setBaseUrl("https://api.example.com/v1");
        dto.setSystemPrompt("你是新的客服提示词");
        dto.getAgent().setMaxIters(7);
        CustomerWorkRuntimeConfig.McpServer mcp = new CustomerWorkRuntimeConfig.McpServer();
        mcp.setName("orders");
        mcp.setUrl("https://mcp.example.com/sse");
        mcp.setTransport("sse");
        dto.setMcpServers(List.of(mcp));
        return dto;
    }

    @Test
    void applyCommitsAllOnSuccess() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        ModelConfig modelConfig = mock(ModelConfig.class);
        Model newChain = mock(Model.class);
        when(newChain.getModelName()).thenReturn("new-chain");
        when(modelConfig.buildChain(any())).thenReturn(newChain);

        Model initial = mock(Model.class);
        when(initial.getModelName()).thenReturn("old-chain");
        MutableDelegatingModel mutableModel = new MutableDelegatingModel(initial);
        NacosPromptService prompt = new NacosPromptService(properties);
        CustomerServiceService css = mock(CustomerServiceService.class);

        RuntimeConfigApplier applier =
            new RuntimeConfigApplier(properties, modelConfig, mutableModel, prompt, css);

        boolean ok = applier.apply(sampleDto(), "sk-plain", null);

        assertTrue(ok);
        assertSame(newChain, mutableModel.current(), "模型链应热替换为新链");
        assertEquals("openai", properties.getModel().getProvider());
        assertEquals("gpt-4o", properties.getModel().getName());
        assertEquals("sk-plain", properties.getModel().getApiKey());
        assertEquals(7, properties.getAgent().getMaxIters());
        assertEquals("你是新的客服提示词", prompt.currentPrompt().orElse(""));
        assertTrue(properties.getMcp().isEnabled());
        assertEquals(1, properties.getMcp().getServers().size());
        verify(css).flushHotAgents();
    }

    @Test
    void buildFailureKeepsOldConfig() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        ModelConfig modelConfig = mock(ModelConfig.class);
        when(modelConfig.buildChain(any())).thenThrow(new IllegalStateException("build boom"));

        Model initial = mock(Model.class);
        when(initial.getModelName()).thenReturn("old-chain");
        MutableDelegatingModel mutableModel = new MutableDelegatingModel(initial);
        NacosPromptService prompt = new NacosPromptService(properties);
        CustomerServiceService css = mock(CustomerServiceService.class);

        RuntimeConfigApplier applier =
            new RuntimeConfigApplier(properties, modelConfig, mutableModel, prompt, css);

        boolean ok = applier.apply(sampleDto(), "sk-plain", null);

        assertFalse(ok);
        assertSame(initial, mutableModel.current(), "构建失败不应替换模型链");
        assertEquals("dashscope", properties.getModel().getProvider(), "配置应保留旧值");
        verify(css, never()).flushHotAgents();
    }

    @Test
    void validationFailureShortCircuits() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        ModelConfig modelConfig = mock(ModelConfig.class);
        Model initial = mock(Model.class);
        when(initial.getModelName()).thenReturn("old-chain");
        MutableDelegatingModel mutableModel = new MutableDelegatingModel(initial);
        NacosPromptService prompt = new NacosPromptService(properties);
        CustomerServiceService css = mock(CustomerServiceService.class);
        RuntimeConfigApplier applier =
            new RuntimeConfigApplier(properties, modelConfig, mutableModel, prompt, css);

        CustomerWorkRuntimeConfig bad = new CustomerWorkRuntimeConfig();
        bad.getModel().setProvider("");   // provider 空白，校验失败
        bad.getModel().setName("");

        assertFalse(applier.apply(bad, null, null));
        verify(modelConfig, never()).buildChain(any());
        verify(css, never()).flushHotAgents();
    }
}
