package com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 运行时配置发布任务 Mapper。 */
public interface RuntimePublishTaskMapper extends BaseMapper<RuntimePublishTask> {

    List<RuntimePublishTask> findDueCandidates(@Param("nowMs") long nowMs, @Param("limit") int limit);

    int claim(@Param("id") String id, @Param("owner") String owner,
              @Param("nowMs") long nowMs, @Param("leaseUntilMs") long leaseUntilMs);

    int attachMetadata(@Param("task") RuntimePublishTask task, @Param("owner") String owner,
                       @Param("nowMs") long nowMs);

    int markPublished(@Param("id") String id, @Param("owner") String owner,
                      @Param("nowMs") long nowMs);

    int markDeliveryFailed(@Param("id") String id, @Param("owner") String owner,
                           @Param("status") String status, @Param("attempts") int attempts,
                           @Param("nextAttemptAtMs") long nextAttemptAtMs,
                           @Param("error") String error, @Param("nowMs") long nowMs);

    RuntimePublishTask findByRevision(@Param("tenantId") String tenantId,
                                      @Param("revision") String revision);

    int updateAckStatus(@Param("tenantId") String tenantId, @Param("revision") String revision,
                        @Param("status") String status, @Param("nowMs") long nowMs);
}
