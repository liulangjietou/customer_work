package com.richard.fyoung.customeradmin.workspace.memory;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 长期记忆存储配置。默认落库（{@code ai_agent_memory} 表）；需要存磁盘时在配置里
 * 显式指定 {@link #diskRoot}，指定后记忆权威副本改存 {@code {disk-root}/{agentCode}/MEMORY.md}。
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.agent-memory")
public class AgentMemoryProperties {

    /** 磁盘存储根目录：留空（默认）= 数据库存储；配置后 = 磁盘存储到该路径。 */
    private String diskRoot = "";
}
