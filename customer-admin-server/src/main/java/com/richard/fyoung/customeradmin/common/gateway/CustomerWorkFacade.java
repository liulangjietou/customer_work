package com.richard.fyoung.customeradmin.common.gateway;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbConnectionSettings;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGateway;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGatewayProvider;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGateways;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Function;

/**
 * 客服端库跨库门面的<b>唯一</b>装配方式。
 *
 * <p><b>为什么需要它</b>：admin 用 {@code spring.autoconfigure.exclude} 关掉了 starter 的自动装配，
 * 凡是要读写客服端库的能力都得自己建一套跨库环境。这套代码有固定套路（惰性建池、探测、
 * 库不可达转业务异常、销毁时关池），此前每个能力域各抄一份——内容风控、评测、badcase、
 * 字典、配额、运营看板、主体配额，7 份 Provider 共 493 行，差异只有下面那几个参数。</p>
 *
 * <p>抄一份的代价不是行数，是<b>散落</b>：套路里任何一条要改（比如池参数、异常转换口径），
 * 就得记得改 7 处；新增一个能力域时最容易发生的是"照着抄但漏了 @PreDestroy"，
 * 而漏了不会报错，只会在反复重启时慢慢泄漏连接池。</p>
 *
 * <p>用法——每个能力域一个 {@code @Bean}，把差异面填进来即可：</p>
 * <pre>{@code
 * @Bean(destroyMethod = "close")
 * public CustomerWorkFacade<BadcaseService> badcaseFacade(
 *         CustomerWorkDbConnection props, AdminCrossDbTenantPlugins plugins) {
 *     return CustomerWorkFacade.builder("badcase-pool", props, plugins)
 *         .mapperXml(BadcaseGatewayFactory.MAPPER_XML_LOCATIONS)
 *         .error("BADCASE-DS-UNAVAILABLE", "客服端库不可达（badcase 与回流目标存放于此）")
 *         .build(BadcaseGatewayFactory::build);
 * }
 * }</pre>
 *
 * <p>连接信息由 {@link CustomerWorkDbConnection} 提供：多数能力域复用
 * {@code admin.content-guard.*} 那一份（这些表同在客服端库，再配一套连接参数只会多一处
 * 要同步维护的配置），而调用统计走自己的 {@code admin.agent-call-stats.app.*}——
 * 抽成接口正是为了让这种差异不必牺牲门面的统一。</p>
 *
 * @param <T> 门面暴露的服务类型，一律直接用 starter 的领域服务，admin 不重写判定逻辑
 * @author owlzhangfq@gmail.com
 */
public final class CustomerWorkFacade<T> {

    private static final Logger log = LoggerFactory.getLogger(CustomerWorkFacade.class);

    /** 跨库连接池默认大小：门面都是低频运维查询，池子不需要大。 */
    private static final int DEFAULT_MAX_POOL_SIZE = 3;

    private final CustomerWorkDbConnection properties;
    private final CrossDbGatewayProvider<T> delegate;
    private final String errorCode;
    private final String errorHint;

    private CustomerWorkFacade(CustomerWorkDbConnection properties, CrossDbGatewayProvider<T> delegate,
                               String errorCode, String errorHint) {
        this.properties = properties;
        this.delegate = delegate;
        this.errorCode = errorCode;
        this.errorHint = errorHint;
    }

    /**
     * 取门面（惰性建连 + 探测 + 缓存）；库不可达时转成带业务语义的异常。
     *
     * <p>惰性是刻意的：admin 启动期绝不触碰客服端库，客服端库挂了不影响后台登录与配置管理。</p>
     */
    public T get() {
        try {
            return delegate.get();
        } catch (CrossDbUnavailableException e) {
            log.error("customer-work datasource unavailable, code={}, url={}",
                errorCode, properties.jdbcUrl(), e);
            throw new BizException(ResultCode.CUSTOMER_WORK_UNAVAILABLE, errorHint + "：" + e.rootMessage());
        }
    }

    /** 销毁时关池。用 {@code @Bean(destroyMethod = "close")} 绑定，别再各自写 {@code @PreDestroy}。 */
    public void close() {
        delegate.close();
    }

    public static Builder builder(String poolName, CustomerWorkDbConnection properties,
                                  AdminCrossDbTenantPlugins tenantPlugins) {
        return new Builder(poolName, properties, tenantPlugins);
    }

    /** 只承载各能力域之间真正不同的那几项。 */
    public static final class Builder {

        private final String poolName;
        private final CustomerWorkDbConnection properties;
        private final AdminCrossDbTenantPlugins tenantPlugins;
        private List<Class<?>> mapperClasses = List.of();
        private List<String> mapperXmlLocations = List.of();
        private int maxPoolSize = DEFAULT_MAX_POOL_SIZE;
        private String errorCode = "CUSTOMER-WORK-DS-UNAVAILABLE";
        private String errorHint = "客服端库不可达";

        private Builder(String poolName, CustomerWorkDbConnection properties,
                        AdminCrossDbTenantPlugins tenantPlugins) {
            this.poolName = poolName;
            this.properties = properties;
            this.tenantPlugins = tenantPlugins;
        }

        /** 无 XML 的 Mapper 接口清单；有 XML 的走 {@link #mapperXml} 靠 namespace 绑定，两者别重复登记。 */
        public Builder mapperClasses(List<Class<?>> classes) {
            this.mapperClasses = classes;
            return this;
        }

        public Builder mapperXml(List<String> locations) {
            this.mapperXmlLocations = locations;
            return this;
        }

        public Builder maxPoolSize(int size) {
            this.maxPoolSize = size;
            return this;
        }

        /**
         * 库不可达时的错误码与面向使用者的提示。
         *
         * @param code 形如 {@code BADCASE-DS-UNAVAILABLE}，进 error 日志便于检索
         * @param hint 说清"这里存的是什么"，运维据此判断影响面
         */
        public Builder error(String code, String hint) {
            this.errorCode = code;
            this.errorHint = hint;
            return this;
        }

        public <T> CustomerWorkFacade<T> build(Function<CrossDbGateway, T> factory) {
            CrossDbGatewayProvider<T> provider = CrossDbGateways.lazy(
                () -> CrossDbConnectionSettings.builder(poolName, properties.jdbcUrl())
                    .credentials(properties.getUsername(), properties.getPassword())
                    .maximumPoolSize(maxPoolSize)
                    .build(),
                mapperClasses, mapperXmlLocations, tenantPlugins::create, factory);
            return new CustomerWorkFacade<>(properties, provider, errorCode, errorHint);
        }
    }
}
