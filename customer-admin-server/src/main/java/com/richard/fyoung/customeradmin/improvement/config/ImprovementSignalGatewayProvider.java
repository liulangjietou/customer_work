package com.richard.fyoung.customeradmin.improvement.config;

import com.richard.fyoung.customeradmin.common.constant.StarterMapperXml;
import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkFacade;
import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardProperties;
import com.richard.fyoung.customeradmin.improvement.jdbc.ImprovementSignalGateway;
import com.richard.fyoung.customeradmin.improvement.mapper.ImprovementSignalMapper;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customerwork.capability.eval.MybatisEvalCaseStore;
import com.richard.fyoung.customerwork.capability.eval.mapper.EvalCaseMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.List;

/** 客服库改进信号门面，惰性建连并复用既有客服库连接参数。 */
@Component
public class ImprovementSignalGatewayProvider {

    private final CustomerWorkFacade<ImprovementSignalGateway> facade;

    public ImprovementSignalGatewayProvider(ContentGuardProperties properties,
                                            AdminCrossDbTenantPlugins tenantPlugins) {
        this.facade = CustomerWorkFacade.builder("improvement-signal-pool", properties, tenantPlugins)
            .mapperClasses(List.of(ImprovementSignalMapper.class))
            .mapperXml(List.of(StarterMapperXml.EVAL_CASE))
            .maxPoolSize(2)
            .error("IMPROVEMENT-SIGNAL-DS-UNAVAILABLE", "客服端库不可达（改进信号与评测用例存放于此）")
            .build(gateway -> new ImprovementSignalGateway(
                gateway.getMapper(ImprovementSignalMapper.class),
                new MybatisEvalCaseStore(gateway.getMapper(EvalCaseMapper.class))));
    }

    public ImprovementSignalGateway get() {
        return facade.get();
    }

    @PreDestroy
    public void close() {
        facade.close();
    }
}
