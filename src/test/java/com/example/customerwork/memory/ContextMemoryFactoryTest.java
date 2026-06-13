package com.example.customerwork.memory;

import com.example.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

/**
 * 短期记忆工厂单测（特性「智能上下文压缩」）：按开关返回普通记忆或自动压缩记忆。
 */
class ContextMemoryFactoryTest {

    private final Model model = mock(Model.class);

    @Test
    void create_shouldReturnPlainMemory_whenCompressionDisabled() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getContext().setCompressionEnabled(false);

        Memory memory = new ContextMemoryFactory(props, model).create();
        assertInstanceOf(InMemoryMemory.class, memory);
    }

    @Test
    void create_shouldReturnAutoContextMemory_whenCompressionEnabled() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getContext().setCompressionEnabled(true);
        props.getContext().setMaxToken(4000);
        props.getContext().setMsgThreshold(20);
        props.getContext().setLastKeep(6);

        Memory memory = new ContextMemoryFactory(props, model).create();
        assertInstanceOf(AutoContextMemory.class, memory);
    }
}
