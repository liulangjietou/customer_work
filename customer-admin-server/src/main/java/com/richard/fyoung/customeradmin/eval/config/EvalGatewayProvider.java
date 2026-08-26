package com.richard.fyoung.customeradmin.eval.config;

import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkDbProperties;
import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkFacade;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customerwork.capability.eval.EvalRunStore;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/**
 * 客服端库评测记录门面的惰性提供者。
 *
 * <p>连接参数复用 {@link CustomerWorkDbProperties}——评测记录与内容风控三表同在客服端库，
 * 再配一套连接参数只会多一处要同步维护的配置（与配额门面同一取舍）。</p>
 *
 * <p>惰性建连，<b>绝不在 admin 启动期触碰客服端库</b>：后台不该因为客服端库没起来就启动不了。</p>
 *
 * <p>跨库 SqlSessionFactory 与 admin 主库挂同一租户插件，默认只读当前有效租户；平台确需全租户汇总时
 * 必须在已校验运营权限后显式进入 {@code CrossTenantOperations}，避免跨库门面成为隔离旁路。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class EvalGatewayProvider {

    private final CustomerWorkFacade<EvalGateway> facade;

    public EvalGatewayProvider(CustomerWorkDbProperties properties, AdminCrossDbTenantPlugins tenantPlugins) {
        this.facade = CustomerWorkFacade.builder("eval-run-pool", properties, tenantPlugins)
            .mapperClasses(EvalGatewayFactory.MAPPER_CLASSES)
            .mapperXml(EvalGatewayFactory.MAPPER_XML_LOCATIONS)
            .readOnly(false)
            .error("EVAL-DS-UNAVAILABLE", "客服端库不可达（评测记录存放于此）")
            .build(EvalGatewayFactory::build);
    }

    /** 取门面（惰性建连 + 探测 + 缓存）；库不可达抛带业务语义的异常。 */
    public EvalRunStore get() {
        return facade.get().runStore();
    }

    /** 数据集治理入口；与运行记录共用连接池，避免同一客服端库重复建池。 */
    public EvalGateway dataset() {
        return facade.get();
    }

    @PreDestroy
    public void close() {
        facade.close();
    }
}
