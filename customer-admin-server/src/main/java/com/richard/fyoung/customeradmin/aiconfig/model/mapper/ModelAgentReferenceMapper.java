package com.richard.fyoung.customeradmin.aiconfig.model.mapper;

import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelAgentReference;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 模型配置到引用智能体的只读投影 Mapper。
 * @author owlzhangfq@gmail.com
 */
public interface ModelAgentReferenceMapper {

    /**
     * 查询主模型和备用模型两条引用链并去重。
     *
     * @param modelId 模型配置 ID
     * @param tenantId 私有模型传当前租户；共享模型跨租户扫描时传 {@code null}
     */
    List<ModelAgentReference> findReferences(@Param("modelId") Long modelId,
                                             @Param("tenantId") String tenantId);
}
