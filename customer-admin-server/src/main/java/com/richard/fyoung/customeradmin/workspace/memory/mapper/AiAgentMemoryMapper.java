package com.richard.fyoung.customeradmin.workspace.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.workspace.memory.entity.AiAgentMemory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 智能体长期记忆 Mapper。由 {@code CustomerAdminServerApplication} 的
 * {@code @MapperScan("...**.mapper")} 自动扫描。
 * @author owlzhangfq@gmail.com
 */
public interface AiAgentMemoryMapper extends BaseMapper<AiAgentMemory> {

    @Insert("""
        INSERT IGNORE INTO ai_agent_memory (agent_code, content, version)
        VALUES (#{agentCode}, #{content}, 1)
        """)
    int insertIfAbsent(@Param("agentCode") String agentCode, @Param("content") String content);

    @Update("""
        UPDATE ai_agent_memory
           SET content = #{content}, version = version + 1
         WHERE agent_code = #{agentCode} AND version = #{expectedVersion}
        """)
    int updateIfVersion(@Param("agentCode") String agentCode,
                        @Param("content") String content,
                        @Param("expectedVersion") long expectedVersion);
}
