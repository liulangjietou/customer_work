package com.richard.fyoung.customeradmin.aiconfig.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelHealthSnapshot;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 模型健康快照 Mapper。 */
public interface AiModelHealthSnapshotMapper extends BaseMapper<AiModelHealthSnapshot> {

    int insertIgnore(AiModelHealthSnapshot snapshot);

    int updateIfNewer(@Param("snapshot") AiModelHealthSnapshot snapshot,
                      @Param("success") boolean success,
                      @Param("authFailure") boolean authFailure,
                      @Param("failureThreshold") int failureThreshold);

    /** 跨租户内部巡检使用；调用方必须显式进入 CrossTenantOperations。 */
    List<AiModelConfig> findDueModels(@Param("limit") int limit);
}
