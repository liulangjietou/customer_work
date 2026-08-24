package com.richard.fyoung.customeradmin.slo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.slo.entity.SloNotificationTask;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** SLO 告警可靠通知任务 Mapper。 */
public interface SloNotificationTaskMapper extends BaseMapper<SloNotificationTask> {

    int insertIgnore(SloNotificationTask task);

    List<SloNotificationTask> findDueCandidates(@Param("nowMs") long nowMs,
                                                @Param("limit") int limit);

    int claim(@Param("id") String id, @Param("owner") String owner,
              @Param("nowMs") long nowMs, @Param("leaseUntilMs") long leaseUntilMs);

    SloNotificationTask lockOwned(@Param("id") String id, @Param("owner") String owner,
                                  @Param("nowMs") long nowMs);

    int markDelivered(@Param("id") String id, @Param("owner") String owner,
                      @Param("recipientCount") int recipientCount, @Param("nowMs") long nowMs);

    int markFailed(@Param("id") String id, @Param("owner") String owner,
                   @Param("attempts") int attempts,
                   @Param("nextAttemptAtMs") long nextAttemptAtMs,
                   @Param("error") String error, @Param("nowMs") long nowMs);

    List<Long> findSloViewUserIds(@Param("tenantId") String tenantId);
}
