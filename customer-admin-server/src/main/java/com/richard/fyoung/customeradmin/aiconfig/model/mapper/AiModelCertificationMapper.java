package com.richard.fyoung.customeradmin.aiconfig.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelCertification;
import org.apache.ibatis.annotations.Param;

/** 模型认证快照 Mapper。 */
public interface AiModelCertificationMapper extends BaseMapper<AiModelCertification> {
    int promoteIfCurrent(@Param("certification") AiModelCertification certification,
                         @Param("secretRefId") Long secretRefId);
}
