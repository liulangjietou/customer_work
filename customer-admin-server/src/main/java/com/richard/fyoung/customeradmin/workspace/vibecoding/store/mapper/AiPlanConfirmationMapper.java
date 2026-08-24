package com.richard.fyoung.customeradmin.workspace.vibecoding.store.mapper;

import com.richard.fyoung.customeradmin.workspace.vibecoding.store.entity.AiPlanConfirmation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AiPlanConfirmationMapper {

    @Insert("""
        INSERT IGNORE INTO ai_plan_confirmation
          (tenant_id, agent_code, session_id, plan_id, status, expire_at)
        VALUES
          (#{tenantId}, #{agentCode}, #{sessionId}, #{planId}, #{status}, #{expireAt})
        """)
    int insert(AiPlanConfirmation row);

    @Select("""
        SELECT tenant_id, agent_code, session_id, plan_id, status, expire_at
          FROM ai_plan_confirmation
         WHERE tenant_id = #{tenantId}
           AND agent_code = #{agentCode}
           AND session_id = #{sessionId}
           AND plan_id = #{planId}
         LIMIT 1
        """)
    AiPlanConfirmation find(@Param("tenantId") String tenantId,
                            @Param("agentCode") String agentCode,
                            @Param("sessionId") String sessionId,
                            @Param("planId") String planId);

    @Update("""
        UPDATE ai_plan_confirmation
           SET status = #{target}, resolved_at = CURRENT_TIMESTAMP
         WHERE tenant_id = #{tenantId}
           AND agent_code = #{agentCode}
           AND session_id = #{sessionId}
           AND plan_id = #{planId}
           AND status = 'PENDING'
           AND (#{target} NOT IN ('APPROVED', 'REJECTED') OR expire_at > CURRENT_TIMESTAMP)
        """)
    int transition(@Param("tenantId") String tenantId,
                   @Param("agentCode") String agentCode,
                   @Param("sessionId") String sessionId,
                   @Param("planId") String planId,
                   @Param("target") String target);
}
