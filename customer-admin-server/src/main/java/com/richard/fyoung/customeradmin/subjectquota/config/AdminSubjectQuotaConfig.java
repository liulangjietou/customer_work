package com.richard.fyoung.customeradmin.subjectquota.config;

import com.richard.fyoung.customeradmin.subjectquota.runtime.AdminUserLevelBinding;
import com.richard.fyoung.customeradmin.subjectquota.runtime.LazyCrossDbHitStore;
import com.richard.fyoung.customeradmin.subjectquota.runtime.LazyCrossDbLevelStore;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customerwork.infra.config.properties.SubjectQuotaProperties;
import com.richard.fyoung.customerwork.infra.counter.InMemoryWindowCounter;
import com.richard.fyoung.customerwork.infra.counter.RedissonWindowCounter;
import com.richard.fyoung.customerwork.infra.counter.WindowCounter;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectContextThreadLocalAccessor;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectLevelResolver;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaGuard;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaHitStore;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaLevelProvider;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaLevelStore;
import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Hooks;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 后台用户配额装配。
 *
 * <p>本模块已 {@code spring.autoconfigure.exclude} 关闭 starter 自动装配，故 starter 的
 * {@code SubjectQuotaConfig} 不会加载，这里手动 new（与 {@code AdminDistributedLockConfig}
 * 等既有配置同一手法）。</p>
 *
 * <p><b>计数器优先用 Redisson</b>：后台多副本时进程内计数等于把额度放大 N 倍。与客服端共用同一个
 * Redis 也不会串——主体类型不同（{@code ADMIN_USER} vs {@code USER}），计数键天然分开，
 * 共享反而让多副本的额度真正合成一份。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AdminSubjectQuotaProperties.class)
public class AdminSubjectQuotaConfig {

    private final AdminSubjectQuotaProperties properties;

    public AdminSubjectQuotaConfig(AdminSubjectQuotaProperties properties) {
        this.properties = properties;
    }

    /**
     * 把主体上下文接进 Reactor 的自动传播。
     *
     * <p><b>为什么非做不可</b>：判定发生在 MVC 拦截器（Tomcat 线程），而 token 的真实用量要到
     * 模型调用之后才知道，那时链路早已切到 Reactor 线程——不接传播，后台用户的额度就只有次数在动、
     * token 永远是 0。admin 此前完全没有这套机制（starter 的自动装配被 exclude 了）。</p>
     *
     * <p><b>只接主体，不顺手把租户也接上</b>：租户上下文在 admin 的 Reactor 链上同样是丢的，
     * 但补它会让多租户开启后，AI 链路里的持久层操作开始真正带上租户过滤——那是一次独立的行为变更，
     * 该单独评估、单独验证，不该搭这次的车。</p>
     *
     * <p>Accessor 无条件注册（它本身没有开销），但全局的自动传播开关只在功能开启时才动——
     * 有全局影响的东西不该在功能关闭时生效。代价是打开配置需要重启。</p>
     */
    @PostConstruct
    public void setUpContextPropagation() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new QuotaSubjectContextThreadLocalAccessor());
        if (properties.isEnabled()) {
            Hooks.enableAutomaticContextPropagation();
            log.info("admin quota subject context propagation enabled");
        }
    }

    @Bean
    public SubjectQuotaLevelStore adminSubjectQuotaLevelStore(SubjectQuotaGatewayProvider gatewayProvider) {
        return new LazyCrossDbLevelStore(gatewayProvider);
    }

    @Bean
    public SubjectQuotaHitStore adminSubjectQuotaHitStore(SubjectQuotaGatewayProvider gatewayProvider) {
        return new LazyCrossDbHitStore(gatewayProvider);
    }

    /**
     * 等级快照：定时刷新关闭、惰性刷新打开。
     *
     * <p>构造时会 reload 一次，此时客服端库若不可达，{@link LazyCrossDbLevelStore} 会把异常
     * 吞成"读取失败"，Provider 保留空快照继续启动——后台不该因为客服端库没起来就起不来。</p>
     */
    @Bean
    public SubjectQuotaLevelProvider adminSubjectQuotaLevelProvider(SubjectQuotaLevelStore adminSubjectQuotaLevelStore) {
        return new SubjectQuotaLevelProvider(adminSubjectQuotaLevelStore, false,
            properties.isEnabled() ? properties.getLazyRefreshMs() : 0L);
    }

    @Bean
    public SubjectLevelResolver adminSubjectLevelResolver(SubjectQuotaLevelProvider adminSubjectQuotaLevelProvider,
                                                          SysUserMapper sysUserMapper) {
        // 复用 starter 的解析器，只把"后台默认档"与缓存参数灌进它认识的配置对象；
        // 客服端那几项（注册默认档、匿名档、API Key 档）在 admin 侧用不到，保持默认即可
        SubjectQuotaProperties starterProperties = new SubjectQuotaProperties();
        starterProperties.setDefaultAdminLevel(properties.getDefaultLevel());
        starterProperties.setLevelCacheTtlMs(properties.getLevelCacheTtlMs());
        return new SubjectLevelResolver(adminSubjectQuotaLevelProvider,
            new AdminUserLevelBinding(sysUserMapper), starterProperties);
    }

    @Bean
    public SubjectQuotaGuard adminSubjectQuotaGuard(SubjectLevelResolver adminSubjectLevelResolver,
                                                    SubjectQuotaHitStore adminSubjectQuotaHitStore,
                                                    ObjectProvider<RedissonClient> redissonProvider) {
        WindowCounter counter = buildCounter(redissonProvider);
        if (properties.isEnabled()) {
            log.info("admin subject quota guard enabled, defaultLevel={}, counter={}",
                properties.getDefaultLevel(), counter.getClass().getSimpleName());
        }
        return new SubjectQuotaGuard(adminSubjectLevelResolver, counter,
            adminSubjectQuotaHitStore, properties.isEnabled());
    }

    private WindowCounter buildCounter(ObjectProvider<RedissonClient> redissonProvider) {
        InMemoryWindowCounter inMemory = new InMemoryWindowCounter();
        RedissonClient redisson = redissonProvider.getIfAvailable();
        if (redisson == null) {
            // Redis 不可用时退进程内：限流是旁路保护，不该因为它拖垮后台的可启动性
            log.info("admin subject quota counter falls back to in-process (no RedissonClient)");
            return inMemory;
        }
        return new RedissonWindowCounter(redisson, "cw:counter:", inMemory);
    }
}
