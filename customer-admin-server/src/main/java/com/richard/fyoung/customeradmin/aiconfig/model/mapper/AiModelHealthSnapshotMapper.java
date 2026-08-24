package com.richard.fyoung.customeradmin.aiconfig.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelHealthSnapshot;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 模型健康快照 Mapper。 */
public interface AiModelHealthSnapshotMapper extends BaseMapper<AiModelHealthSnapshot> {

    int insertIgnore(AiModelHealthSnapshot snapshot);

    /** 同一部署状态机的行锁读取；调用方必须处于事务中。 */
    AiModelHealthSnapshot lockSnapshot(@Param("modelConfigId") Long modelConfigId,
                                       @Param("tenantId") String tenantId);

    /** 跨租户内部巡检使用；调用方必须显式进入 CrossTenantOperations。 */
    List<AiModelConfig> findDueModels(@Param("limit") int limit);

    /** 跨租户查找已到期人工覆盖；调用方必须显式进入 CrossTenantOperations。 */
    List<AiModelConfig> findExpiredOverrideModels(@Param("now") java.time.LocalDateTime now,
                                                  @Param("limit") int limit);
}
