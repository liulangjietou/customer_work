package com.richard.fyoung.customeradmin.tenant.access.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.tenant.access.entity.TenantAccessPublishTask;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 租户访问快照发布任务 Mapper。 */
public interface TenantAccessPublishTaskMapper extends BaseMapper<TenantAccessPublishTask> {

    int supersedePending(@Param("tenantId") String tenantId, @Param("nowMs") long nowMs);

    List<TenantAccessPublishTask> findDueCandidates(@Param("nowMs") long nowMs, @Param("limit") int limit);

    int claim(@Param("id") String id, @Param("tenantId") String tenantId,
              @Param("owner") String owner, @Param("nowMs") long nowMs,
              @Param("leaseUntilMs") long leaseUntilMs);

    int markPublished(@Param("id") String id, @Param("owner") String owner,
                      @Param("nowMs") long nowMs);

    int markDeliveryFailed(@Param("id") String id, @Param("owner") String owner,
                           @Param("status") String status, @Param("attempts") int attempts,
                           @Param("nextAttemptAtMs") long nextAttemptAtMs,
                           @Param("error") String error, @Param("nowMs") long nowMs);

    int countNewerTasks(@Param("tenantId") String tenantId, @Param("seq") long seq);

    TenantAccessPublishTask findLatest(@Param("tenantId") String tenantId);
}
