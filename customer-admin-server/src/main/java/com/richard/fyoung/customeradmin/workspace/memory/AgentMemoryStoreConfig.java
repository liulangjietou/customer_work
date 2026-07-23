package com.richard.fyoung.customeradmin.workspace.memory;

import com.richard.fyoung.customeradmin.workspace.memory.mapper.AiAgentMemoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.nio.file.Path;

/**
 * 长期记忆权威存储装配：默认 JDBC 落库；配置 {@code admin.agent-memory.disk-root} 后切磁盘实现。
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class AgentMemoryStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryStoreConfig.class);

    @Bean
    public AgentMemoryStore agentMemoryStore(AgentMemoryProperties properties, AiAgentMemoryMapper memoryMapper) {
        if (StringUtils.hasText(properties.getDiskRoot())) {
            log.info("agent memory store: disk, root={}", properties.getDiskRoot());
            return new DiskAgentMemoryStore(Path.of(properties.getDiskRoot()));
        }
        log.info("agent memory store: jdbc (ai_agent_memory)");
        return new JdbcAgentMemoryStore(memoryMapper);
    }
}
