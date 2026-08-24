package com.richard.fyoung.customeradmin.governance.change.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.governance.change.entity.AiGovernedChangeRequest;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 高风险变更状态的条件更新边界。 */
public interface GovernedChangeRequestMapper extends BaseMapper<AiGovernedChangeRequest> {

    @Update("""
        UPDATE ai_governed_change_request
        SET status = 'EXECUTING', checker_id = #{checkerId}, checker_name = #{checkerName},
            decision_reason = #{reason}, decided_at = #{now}, update_time = #{now}
        WHERE id = #{id} AND tenant_id = #{tenantId} AND status = 'PENDING'
          AND maker_id <> #{checkerId} AND expires_at > #{now}
        """)
    int claimForExecution(@Param("id") String id, @Param("tenantId") String tenantId,
                          @Param("checkerId") Long checkerId,
                          @Param("checkerName") String checkerName,
                          @Param("reason") String reason,
                          @Param("now") LocalDateTime now);

    @Update("""
        UPDATE ai_governed_change_request
        SET status = 'REJECTED', checker_id = #{checkerId}, checker_name = #{checkerName},
            decision_reason = #{reason}, decided_at = #{now}, update_time = #{now}
        WHERE id = #{id} AND tenant_id = #{tenantId} AND status = 'PENDING'
          AND maker_id <> #{checkerId} AND expires_at > #{now}
        """)
    int rejectPending(@Param("id") String id, @Param("tenantId") String tenantId,
                      @Param("checkerId") Long checkerId,
                      @Param("checkerName") String checkerName,
                      @Param("reason") String reason,
                      @Param("now") LocalDateTime now);

    @Update("""
        UPDATE ai_governed_change_request
        SET status = 'EXECUTED', result_json = #{resultJson}, executed_at = #{now}, update_time = #{now}
        WHERE id = #{id} AND tenant_id = #{tenantId} AND status = 'EXECUTING'
        """)
    int markExecuted(@Param("id") String id, @Param("tenantId") String tenantId,
                     @Param("resultJson") String resultJson, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE ai_governed_change_request
        SET status = 'FAILED', failure_code = #{failureCode}, executed_at = #{now}, update_time = #{now}
        WHERE id = #{id} AND tenant_id = #{tenantId} AND status = 'EXECUTING'
        """)
    int markFailed(@Param("id") String id, @Param("tenantId") String tenantId,
                   @Param("failureCode") String failureCode, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE ai_governed_change_request
        SET status = 'EXPIRED', update_time = #{now}
        WHERE id = #{id} AND tenant_id = #{tenantId} AND status = 'PENDING' AND expires_at <= #{now}
        """)
    int markExpired(@Param("id") String id, @Param("tenantId") String tenantId,
                    @Param("now") LocalDateTime now);

    @InterceptorIgnore(tenantLine = "1")
    @Select("""
        SELECT * FROM ai_governed_change_request
        WHERE status = 'PENDING' AND expires_at <= #{now}
        ORDER BY expires_at ASC LIMIT #{limit}
        """)
    List<AiGovernedChangeRequest> selectExpiredAcrossTenants(
        @Param("now") LocalDateTime now, @Param("limit") int limit);

    @InterceptorIgnore(tenantLine = "1")
    @Select("""
        SELECT * FROM ai_governed_change_request
        WHERE status = 'EXECUTING' AND update_time <= #{cutoff}
        ORDER BY update_time ASC LIMIT #{limit}
        """)
    List<AiGovernedChangeRequest> selectStaleExecutingAcrossTenants(
        @Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Update("""
        UPDATE ai_governed_change_request
        SET status = 'FAILED', failure_code = 'GOVERNED_CHANGE_EXECUTION_TIMEOUT',
            executed_at = #{now}, update_time = #{now}
        WHERE id = #{id} AND tenant_id = #{tenantId} AND status = 'EXECUTING'
          AND update_time <= #{cutoff}
        """)
    int markExecutionTimedOut(@Param("id") String id, @Param("tenantId") String tenantId,
                              @Param("cutoff") LocalDateTime cutoff,
                              @Param("now") LocalDateTime now);
}
