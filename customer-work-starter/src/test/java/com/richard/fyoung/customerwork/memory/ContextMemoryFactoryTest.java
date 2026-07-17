package com.richard.fyoung.customerwork.memory;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * 上下文压缩配置工厂单测（特性「智能上下文压缩」，AgentScope 2.0 迁移版）：
 * 关闭压缩返回 null；开启压缩返回按配置装配的 {@link CompactionConfig}。
 * @author owlzhangfq@gmail.com
 */
class ContextMemoryFactoryTest {

    private final Model model = mock(Model.class);

    @Test
    void createCompaction_shouldReturnNull_whenCompressionDisabled() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getContext().setCompressionEnabled(false);

        assertNull(new ContextMemoryFactory(props, model).createCompaction());
    }

    @Test
    void createCompaction_shouldReturnConfiguredCompaction_whenEnabled() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getContext().setCompressionEnabled(true);
        props.getContext().setMaxToken(4000);
        props.getContext().setMsgThreshold(20);
        props.getContext().setLastKeep(6);

        CompactionConfig compaction = new ContextMemoryFactory(props, model).createCompaction();
        assertNotNull(compaction);
        assertEquals(4000, compaction.getTriggerTokens());
        assertEquals(20, compaction.getTriggerMessages());
        assertEquals(6, compaction.getKeepMessages());
    }
}
