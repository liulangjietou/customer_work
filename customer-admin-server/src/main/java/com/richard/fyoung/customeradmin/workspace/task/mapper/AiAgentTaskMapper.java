package com.richard.fyoung.customeradmin.workspace.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.workspace.task.entity.AiAgentTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 智能体后台任务 Mapper。查询条件都能用 LambdaQueryWrapper 表达，无需 XML。
 * @author owlzhangfq@gmail.com
 */
public interface AiAgentTaskMapper extends BaseMapper<AiAgentTask> {

    @Update("""
        UPDATE ai_agent_task
        SET heartbeat_at = #{heartbeatAt}, lease_until = #{leaseUntil}, updated_at = #{heartbeatAt}
        WHERE owner_id = #{ownerId} AND status IN ('PENDING', 'RUNNING') AND cancel_requested = 0
        """)
    int heartbeatOwned(@Param("ownerId") String ownerId,
                       @Param("heartbeatAt") LocalDateTime heartbeatAt,
                       @Param("leaseUntil") LocalDateTime leaseUntil);

    @Select("""
        <script>
        SELECT * FROM ai_agent_task
        WHERE status IN ('PENDING', 'RUNNING')
          AND cancel_requested = 0
          AND replayable = 1
          AND attempt_count &lt; #{maxAttempts}
          AND (lease_until IS NULL OR lease_until &lt; #{now})
        ORDER BY COALESCE(lease_until, created_at), id
        LIMIT #{limit}
        </script>
        """)
    List<AiAgentTask> selectExpiredReplayable(@Param("now") LocalDateTime now,
                                               @Param("maxAttempts") int maxAttempts,
                                               @Param("limit") int limit);

    @Update("""
        <script>
        UPDATE ai_agent_task
        SET owner_id = #{newOwner}, lease_until = #{leaseUntil}, heartbeat_at = #{now},
            attempt_count = attempt_count + 1, status = 'PENDING', error_message = NULL,
            started_at = NULL, updated_at = #{now}
        WHERE task_id = #{taskId}
          AND status IN ('PENDING', 'RUNNING')
          AND cancel_requested = 0
          AND replayable = 1
          AND attempt_count &lt; #{maxAttempts}
          AND (lease_until IS NULL OR lease_until &lt; #{now})
        </script>
        """)
    int claimExpired(@Param("taskId") String taskId,
                     @Param("newOwner") String newOwner,
                     @Param("now") LocalDateTime now,
                     @Param("leaseUntil") LocalDateTime leaseUntil,
                     @Param("maxAttempts") int maxAttempts);

    @Update("""
        <script>
        UPDATE ai_agent_task
        SET status = #{status}, updated_at = #{now}, heartbeat_at = #{now}, lease_until = #{leaseUntil}
        <if test="startedAt != null">, started_at = COALESCE(started_at, #{startedAt})</if>
        <if test="finishedAt != null">, finished_at = #{finishedAt}, lease_until = NULL</if>
        <if test="result != null">, result = #{result}</if>
        <if test="errorMessage != null">, error_message = #{errorMessage}</if>
        WHERE task_id = #{taskId} AND owner_id = #{ownerId}
          AND status IN ('PENDING', 'RUNNING') AND cancel_requested = 0
        </script>
        """)
    int updateOwnedStatus(@Param("taskId") String taskId,
                          @Param("ownerId") String ownerId,
                          @Param("status") String status,
                          @Param("now") LocalDateTime now,
                          @Param("leaseUntil") LocalDateTime leaseUntil,
                          @Param("startedAt") LocalDateTime startedAt,
                          @Param("finishedAt") LocalDateTime finishedAt,
                          @Param("result") String result,
                          @Param("errorMessage") String errorMessage);

    @Update("""
        <script>
        UPDATE ai_agent_task
        SET status = 'FAILED', error_message = #{errorMessage}, finished_at = #{now},
            lease_until = NULL, updated_at = #{now}
        WHERE status IN ('PENDING', 'RUNNING') AND cancel_requested = 0
          AND (lease_until IS NULL OR lease_until &lt; #{now})
          AND (replayable = 0 OR attempt_count &gt;= #{maxAttempts})
        </script>
        """)
    int failExpiredUnrecoverable(@Param("now") LocalDateTime now,
                                 @Param("maxAttempts") int maxAttempts,
                                 @Param("errorMessage") String errorMessage);

    @Update("""
        UPDATE ai_agent_task
        SET status = 'CANCELLED', finished_at = #{now}, lease_until = NULL, updated_at = #{now}
        WHERE task_id = #{taskId} AND owner_id = #{ownerId} AND status IN ('PENDING', 'RUNNING')
        """)
    int markOwnedCancelled(@Param("taskId") String taskId,
                           @Param("ownerId") String ownerId,
                           @Param("now") LocalDateTime now);
}
