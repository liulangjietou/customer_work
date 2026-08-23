package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelImpactItemVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelImpactVO;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.ModelImpactMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 模型影响查询与删除/禁用/凭据轮换共用预检。 */
@Service
public class ModelImpactService {

    private final ModelImpactMapper impactMapper;

    public ModelImpactService(ModelImpactMapper impactMapper) {
        this.impactMapper = impactMapper;
    }

    public ModelImpactVO query(AiModelConfig model, String tenantScope, String action) {
        List<ModelImpactItemVO> items = CrossTenantOperations.execute(
            () -> impactMapper.findImpacts(model.getId(), tenantScope));
        int blockers = (int) items.stream().filter(item -> Boolean.TRUE.equals(item.getBlocking())).count();
        Map<String, Long> counts = items.stream().collect(Collectors.groupingBy(
            ModelImpactItemVO::getResourceType, LinkedHashMap::new, Collectors.counting()));
        return new ModelImpactVO(model.getId(), action, items.size(), blockers, blockers == 0,
            Map.copyOf(counts), List.copyOf(items));
    }

    public ModelImpactVO requireAllowed(AiModelConfig model, String tenantScope, String action) {
        ModelImpactVO impact = query(model, tenantScope, action);
        if (!impact.allowed()) {
            throw new BizException(ResultCode.RESOURCE_IN_USE,
                "模型部署存在 " + impact.blockerCount() + " 个生效引用，无法" + actionLabel(action));
        }
        return impact;
    }

    private String actionLabel(String action) {
        if ("DISABLE".equals(action)) {
            return "禁用";
        }
        return "ROTATE".equals(action) ? "轮换凭据" : "删除";
    }
}
