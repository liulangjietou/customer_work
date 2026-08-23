package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import java.util.List;
import java.util.Map;

/** 模型影响汇总，也是删除和禁用共用的预检结果。 */
public record ModelImpactVO(Long modelId,
                            String action,
                            int totalCount,
                            int blockerCount,
                            boolean allowed,
                            Map<String, Long> countsByType,
                            List<ModelImpactItemVO> items) {
}
