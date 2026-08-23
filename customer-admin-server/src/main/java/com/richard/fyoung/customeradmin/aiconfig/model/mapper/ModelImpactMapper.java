package com.richard.fyoung.customeradmin.aiconfig.model.mapper;

import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelImpactItemVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 模型部署到智能体、在线实验与既有控制面资源的影响投影 Mapper。 */
public interface ModelImpactMapper {

    List<ModelImpactItemVO> findImpacts(@Param("modelId") Long modelId,
                                        @Param("tenantId") String tenantId);
}
