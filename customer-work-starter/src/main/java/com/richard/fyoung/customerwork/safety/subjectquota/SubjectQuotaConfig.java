package com.richard.fyoung.customerwork.safety.subjectquota;

import com.richard.fyoung.customerwork.core.constant.StoreModes;
import com.richard.fyoung.customerwork.data.user.UserAccountService;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.SubjectQuotaProperties;
import com.richard.fyoung.customerwork.infra.counter.InMemoryWindowCounter;
import com.richard.fyoung.customerwork.infra.counter.WindowCounter;
import com.richard.fyoung.customerwork.safety.security.UserJwtService;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContextThreadLocalAccessor;
import com.richard.fyoung.customerwork.safety.subjectquota.mapper.SubjectQuotaHitMapper;
import com.richard.fyoung.customerwork.safety.subjectquota.mapper.SubjectQuotaLevelMapper;
import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 主体级速率配额装配：Store 按 {@code store-mode} 选实现，Guard 按 {@code enabled} 决定是否真的拦。
 *
 * <p>无论开关是否打开都注册 {@link QuotaSubjectContextThreadLocalAccessor}：Accessor 只是让
 * ThreadLocal 能跨线程还原，本身没有开销；而"开关打开后才注册"会让运行期动态开启（配置中心下发）
 * 变成一个必须重启才生效的操作。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class SubjectQuotaConfig {

    private static final Logger log = LoggerFactory.getLogger(SubjectQuotaConfig.class);

    @PostConstruct
    public void registerContextAccessor() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new QuotaSubjectContextThreadLocalAccessor());
        ContextRegistry.getInstance().registerThreadLocalAccessor(
            new AgentInvocationIdentityContextThreadLocalAccessor());
    }

    @Bean
    @ConditionalOnMissingBean
    public SubjectQuotaLevelStore subjectQuotaLevelStore(CustomerWorkProperties properties,
                                                         ObjectProvider<SubjectQuotaLevelMapper> mapperProvider) {
        SubjectQuotaProperties cfg = properties.getSubjectQuota();
        if (!StoreModes.isJdbc(cfg.getStoreMode())) {
            return new InMemorySubjectQuotaLevelStore();
        }
        SubjectQuotaLevelMapper mapper = mapperProvider.getIfAvailable();
        if (mapper == null) {
            // 配了 jdbc 却没有 Mapper（持久层未装配）：让位给内存实现而不是启动失败——
            // 限流是旁路保护，不该拖垮主链路的可启动性；配置没生效这件事由 error 日志暴露
            log.error("subject-quota store-mode=jdbc but SubjectQuotaLevelMapper unavailable, "
                + "fallback to in-memory, code={}", "SQUOTA-LEVEL-MAPPER-MISSING");
            return new InMemorySubjectQuotaLevelStore();
        }
        log.info("subject quota level store ready (jdbc)");
        return new MybatisSubjectQuotaLevelStore(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public SubjectQuotaHitStore subjectQuotaHitStore(CustomerWorkProperties properties,
                                                     ObjectProvider<SubjectQuotaHitMapper> mapperProvider) {
        SubjectQuotaProperties cfg = properties.getSubjectQuota();
        if (!StoreModes.isJdbc(cfg.getStoreMode())) {
            return new InMemorySubjectQuotaHitStore();
        }
        SubjectQuotaHitMapper mapper = mapperProvider.getIfAvailable();
        if (mapper == null) {
            log.error("subject-quota store-mode=jdbc but SubjectQuotaHitMapper unavailable, "
                + "fallback to in-memory, code={}", "SQUOTA-HIT-MAPPER-MISSING");
            return new InMemorySubjectQuotaHitStore();
        }
        return new MybatisSubjectQuotaHitStore(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public SubjectQuotaLevelProvider subjectQuotaLevelProvider(CustomerWorkProperties properties,
                                                               SubjectQuotaLevelStore store) {
        SubjectQuotaProperties cfg = properties.getSubjectQuota();
        // 只有 jdbc 模式才需要轮询：内存 Store 的变更是同进程的，写入即生效，轮询纯属空转
        boolean refreshEnabled = cfg.isEnabled() && StoreModes.isJdbc(cfg.getStoreMode());
        return new SubjectQuotaLevelProvider(store, refreshEnabled);
    }

    @Bean
    @ConditionalOnMissingBean
    public SubjectLevelResolver subjectLevelResolver(CustomerWorkProperties properties,
                                                     SubjectQuotaLevelProvider levelProvider,
                                                     ObjectProvider<SubjectLevelBinding> bindingProvider) {
        return new SubjectLevelResolver(levelProvider,
            bindingProvider.getIfAvailable(), properties.getSubjectQuota());
    }

    @Bean
    @ConditionalOnMissingBean
    public SubjectQuotaGuard subjectQuotaGuard(CustomerWorkProperties properties,
                                               SubjectLevelResolver levelResolver,
                                               SubjectQuotaHitStore hitStore,
                                               ObjectProvider<WindowCounter> counterProvider) {
        SubjectQuotaProperties cfg = properties.getSubjectQuota();
        WindowCounter counter = counterProvider.getIfAvailable();
        if (cfg.isEnabled()) {
            log.info("subject quota guard enabled, storeMode={}, defaultUserLevel={}, anonymousLevel={}",
                cfg.getStoreMode(), cfg.getDefaultUserLevel(), cfg.getAnonymousLevel());
        }
        return new SubjectQuotaGuard(levelResolver,
            counter == null ? new InMemoryWindowCounter() : counter, hitStore, cfg.isEnabled());
    }

    /**
     * HTTP 侧判定入口。
     *
     * <p>用 {@code @Bean} 而非给过滤器加 {@code @Component}：{@code @WebFluxTest} 切片会按类型
     * 自动纳入所有 {@code WebFilter} Bean，切片里却没有 {@link UserJwtService}，那样每个控制器
     * 切片测试都会因为一个与它无关的过滤器加载失败（{@code UserAuthWebFilter} 同样的理由）。</p>
     */
    // 仅响应式栈装配：WebFilter 在 Servlet 栈（admin）下不生效也不该存在。
    // admin 侧的主体配额判定走 MVC 的 AdminQuotaInterceptor，是另一条实现。
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnMissingBean
    public SubjectQuotaWebFilter subjectQuotaWebFilter(CustomerWorkProperties properties,
                                                       SubjectQuotaGuard guard,
                                                       ObjectProvider<UserJwtService> jwtServiceProvider,
                                                       ObjectProvider<UserAccountService> accountServiceProvider) {
        return new SubjectQuotaWebFilter(properties, guard, jwtServiceProvider.getIfAvailable(),
            accountServiceProvider.getIfAvailable());
    }
}
