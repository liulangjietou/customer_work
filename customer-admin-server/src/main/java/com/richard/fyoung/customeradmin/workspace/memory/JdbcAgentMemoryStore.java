package com.richard.fyoung.customeradmin.workspace.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.workspace.memory.entity.AiAgentMemory;
import com.richard.fyoung.customeradmin.workspace.memory.mapper.AiAgentMemoryMapper;

import java.util.Optional;

/**
 * 长期记忆权威存储的 JDBC 实现（默认）：落库 {@code customer_admin.ai_agent_memory}，
 * 每个智能体一行（agent_code 唯一键），update_time 由数据库维护。
 * @author owlzhangfq@gmail.com
 */
public class JdbcAgentMemoryStore implements AgentMemoryStore {

    private final AiAgentMemoryMapper memoryMapper;

    public JdbcAgentMemoryStore(AiAgentMemoryMapper memoryMapper) {
        this.memoryMapper = memoryMapper;
    }

    @Override
    public Optional<AgentMemorySnapshot> load(String agentCode) {
        AiAgentMemory row = selectByAgentCode(agentCode);
        if (row == null || row.getContent() == null) {
            return Optional.empty();
        }
        return Optional.of(new AgentMemorySnapshot(row.getContent(), row.getUpdateTime()));
    }

    @Override
    public void save(String agentCode, String content) {
        AiAgentMemory row = selectByAgentCode(agentCode);
        if (row == null) {
            AiAgentMemory insert = new AiAgentMemory();
            insert.setAgentCode(agentCode);
            insert.setContent(content);
            memoryMapper.insert(insert);
            return;
        }
        row.setContent(content);
        memoryMapper.updateById(row);
    }

    @Override
    public void delete(String agentCode) {
        memoryMapper.delete(new LambdaQueryWrapper<AiAgentMemory>().eq(AiAgentMemory::getAgentCode, agentCode));
    }

    private AiAgentMemory selectByAgentCode(String agentCode) {
        return memoryMapper.selectOne(new LambdaQueryWrapper<AiAgentMemory>().eq(AiAgentMemory::getAgentCode, agentCode));
    }
}
