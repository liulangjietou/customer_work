package com.richard.fyoung.customeradmin.improvement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.improvement.entity.AgentImprovementCase;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 改进闭环状态与自动化租约 Mapper。 */
public interface AgentImprovementCaseMapper extends BaseMapper<AgentImprovementCase> {

    AgentImprovementCase lockById(@Param("id") Long id);

    List<AgentImprovementCase> findDueCandidates(@Param("nowMs") long nowMs,
                                                  @Param("limit") int limit);

    int claim(@Param("id") Long id, @Param("owner") String owner,
              @Param("nowMs") long nowMs, @Param("leaseUntilMs") long leaseUntilMs);
}
