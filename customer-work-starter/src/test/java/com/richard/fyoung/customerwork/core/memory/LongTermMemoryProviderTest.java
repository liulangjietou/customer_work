package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.mem0.Mem0LongTermMemory;
import io.agentscope.core.memory.reme.ReMeLongTermMemory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 长期记忆提供方单测：默认 provider=memory 返回内置内存实现。
 * （bailian 实现需真实百炼凭据，由集成环境验证。）
 * @author owlzhangfq@gmail.com
 */
class LongTermMemoryProviderTest {

    private LongTermMemoryProvider provider(CustomerWorkProperties props) {
        return new LongTermMemoryProvider(props,
            new InMemoryLongTermMemoryStore(),
            new com.richard.fyoung.customerwork.core.support.InMemoryTestFactLog(false));
    }

    @Test
    void create_shouldReturnInMemory_byDefault() {
        LongTermMemory ltm = provider(new CustomerWorkProperties()).create("tenantA");
        assertInstanceOf(InMemoryLongTermMemory.class, ltm);
    }

    @Test
    void create_shouldReturnMem0_whenProviderMem0() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getMemory().setProvider("mem0");
        props.getMemory().getMem0().setApiKey("m0-test");
        assertInstanceOf(Mem0LongTermMemory.class, provider(props).create("tenantA"));
    }

    @Test
    void create_shouldReturnReMe_whenProviderReMe() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getMemory().setProvider("reme");
        props.getMemory().getReme().setApiBaseUrl("http://localhost:8001");
        assertInstanceOf(ReMeLongTermMemory.class, provider(props).create("tenantA"));
    }
}
