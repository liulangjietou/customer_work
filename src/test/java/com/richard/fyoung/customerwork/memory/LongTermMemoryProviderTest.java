package com.richard.fyoung.customerwork.memory;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.memory.LongTermMemory;
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
            new LongTermMemoryStore(),
            new FactLog(false, Path.of("target/test-facts")));
    }

    @Test
    void create_shouldReturnInMemory_byDefault() {
        LongTermMemory ltm = provider(new CustomerWorkProperties()).create("tenantA");
        assertInstanceOf(InMemoryLongTermMemory.class, ltm);
    }
}
