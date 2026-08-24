package com.richard.fyoung.customeradmin.slo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.slo.entity.SloPolicy;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** SLO 策略 Mapper。 */
public interface SloPolicyMapper extends BaseMapper<SloPolicy> {

    List<SloPolicy> findDueCandidates(@Param("nowMs") long nowMs, @Param("limit") int limit);

    int claimEvaluation(@Param("id") Long id, @Param("owner") String owner,
                        @Param("nowMs") long nowMs, @Param("leaseUntilMs") long leaseUntilMs);

    int markEvaluationSuccess(@Param("id") Long id, @Param("owner") String owner,
                              @Param("nextEvaluationAtMs") long nextEvaluationAtMs,
                              @Param("status") String status,
                              @Param("evaluatedAt") LocalDateTime evaluatedAt);

    int markEvaluationFailure(@Param("id") Long id, @Param("owner") String owner,
                              @Param("nextEvaluationAtMs") long nextEvaluationAtMs,
                              @Param("error") String error,
                              @Param("evaluatedAt") LocalDateTime evaluatedAt);

    int scheduleNow(@Param("id") Long id, @Param("tenantId") String tenantId,
                    @Param("nowMs") long nowMs);
}
