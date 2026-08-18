package com.richard.fyoung.customerwork.capability.approval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.capability.approval.entity.ApprovalRequestDO;
import org.apache.ibatis.annotations.Param;

/**
 * 审批工单 Mapper（由 {@code CustomerWorkPersistenceConfig} 的 {@code @MapperScan} 扫描绑定，不加 {@code @Mapper}）。
 *
 * <p>基础 CRUD 走 {@link BaseMapper}；仅 {@link #upsert} 因需要 {@code ON DUPLICATE KEY UPDATE}
 * 语义写在 XML 中。</p>
 * @author owlzhangfq@gmail.com
 */
public interface ApprovalMapper extends BaseMapper<ApprovalRequestDO> {

    /** 新建或按主键覆盖（对应旧 UPSERT_SQL 的 INSERT ... ON DUPLICATE KEY UPDATE 全字段）。 */
    int upsert(ApprovalRequestDO record);

    int decide(@Param("id") String id, @Param("target") String target,
               @Param("operator") String operator, @Param("note") String note,
               @Param("decidedAtMs") long decidedAtMs);

    int claimExecution(@Param("id") String id, @Param("maxAttempts") int maxAttempts,
                       @Param("marker") String marker);

    int completeExecution(@Param("id") String id, @Param("expectedMarker") String expectedMarker,
                          @Param("target") String target,
                          @Param("failureReason") String failureReason);

    int recoverStuckExecutions(@Param("startedBeforeMs") long startedBeforeMs);
}
