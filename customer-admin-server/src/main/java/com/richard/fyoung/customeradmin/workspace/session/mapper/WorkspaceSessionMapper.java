package com.richard.fyoung.customeradmin.workspace.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.workspace.session.entity.WorkspaceSession;
import org.apache.ibatis.annotations.Param;

/** 工作区会话归属 Mapper。所有语句显式携带 tenantId，拦截器仅作第二道保险。 */
public interface WorkspaceSessionMapper extends BaseMapper<WorkspaceSession> {

    int insertIgnore(@Param("tenantId") String tenantId,
                     @Param("agentCode") String agentCode,
                     @Param("sessionId") String sessionId,
                     @Param("ownerUserId") Long ownerUserId,
                     @Param("nowMs") long nowMs);

    WorkspaceSession findByResource(@Param("tenantId") String tenantId,
                                    @Param("agentCode") String agentCode,
                                    @Param("sessionId") String sessionId);

    int countState(@Param("stateUserId") String stateUserId,
                   @Param("sessionId") String sessionId);
}
