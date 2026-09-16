package com.richard.fyoung.customeradmin.aiconfig.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 智能体 Mapper。
 * @author owlzhangfq@gmail.com
 */
public interface AiAgentMapper extends BaseMapper<AiAgent> {

    /** 在保存事务内锁定预期修订；后续校验失败时修订推进随事务回滚。 */
    @Update("UPDATE ai_agent SET runtime_revision = runtime_revision + 1 "
        + "WHERE id = #{id} AND runtime_revision = #{revision} AND deleted = 0")
    int claimRevision(@Param("id") Long id, @Param("revision") long revision);

    /** 原子推进运行时修订，供所有 Admin Pod 在下一次缓存读取时感知变更。 */
    @Update("UPDATE ai_agent SET runtime_revision = runtime_revision + 1, update_time = NOW() "
        + "WHERE agent_code = #{agentCode} AND deleted = 0")
    int bumpRuntimeRevision(@Param("agentCode") String agentCode);
}
