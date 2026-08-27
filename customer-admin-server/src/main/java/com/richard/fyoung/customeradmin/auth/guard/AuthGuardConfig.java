package com.richard.fyoung.customeradmin.auth.guard;

import com.richard.fyoung.customeradmin.publicdeploy.PublicDeploymentProperties;
import com.richard.fyoung.customerwork.infra.counter.InMemoryWindowCounter;
import com.richard.fyoung.customerwork.infra.counter.RedissonWindowCounter;
import com.richard.fyoung.customerwork.infra.counter.WindowCounter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册与登录防滥用能力的装配。
 *
 * <p>{@code @EnableConfigurationProperties} 是 {@link RegistrationGuardProperties} 的唯一注册入口
 * （它只有 {@code @ConfigurationProperties}，没有 {@code @Component}），删掉会在启动时报
 * {@code NoSuchBeanDefinitionException}——本项目在批量重构配置类时踩过这个坑。</p>
 *
 * <p>计数器与验证码存储都遵循同一个降级方向：Redis 可用则用 Redis（多副本共享），
 * 不可用退进程内而不是放行。防滥用能力不能因为基础设施抖动就整体消失。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RegistrationGuardProperties.class)
public class AuthGuardConfig {

    /** Redis 键前缀与配额计数器一致，靠各自的业务键前缀区分用途。 */
    private static final String COUNTER_KEY_PREFIX = "cw:counter:";

    /**
     * 注册限流与登录锁定共用的窗口计数器。
     *
     * <p>刻意与配额那套各建一个实例：两者的生命周期与降级判定互不相干，
     * 共享一个 Bean 只会让"配额模块关掉时限流跟着失效"这类耦合悄悄出现。</p>
     */
    @Bean
    public WindowCounter authGuardWindowCounter(ObjectProvider<RedissonClient> redissonProvider) {
        InMemoryWindowCounter inMemory = new InMemoryWindowCounter();
        RedissonClient redisson = redissonProvider.getIfAvailable();
        if (redisson == null) {
            log.info("auth guard counter falls back to in-process (no RedissonClient)");
            return inMemory;
        }
        return new RedissonWindowCounter(redisson, COUNTER_KEY_PREFIX, inMemory);
    }

    @Bean
    public CaptchaStore captchaStore(ObjectProvider<RedissonClient> redissonProvider) {
        InMemoryCaptchaStore inMemory = new InMemoryCaptchaStore();
        RedissonClient redisson = redissonProvider.getIfAvailable();
        if (redisson == null) {
            log.info("captcha store falls back to in-process (no RedissonClient)");
            return inMemory;
        }
        return new RedissonCaptchaStore(redisson, inMemory);
    }

    @Bean
    public CaptchaService captchaService(CaptchaStore captchaStore,
                                         RegistrationGuardProperties properties) {
        return new CaptchaService(captchaStore, properties.getCaptcha());
    }

    @Bean
    public RegistrationGuard registrationGuard(RegistrationGuardProperties properties,
                                               PublicDeploymentProperties publicDeployment,
                                               CaptchaService captchaService,
                                               WindowCounter authGuardWindowCounter) {
        if (publicDeployment.isEnabled()) {
            log.info("public deployment: registration captcha and email are enforced regardless of config");
        }
        return new RegistrationGuard(properties, publicDeployment, captchaService, authGuardWindowCounter);
    }

    @Bean
    public LoginAttemptGuard loginAttemptGuard(RegistrationGuardProperties properties,
                                               PublicDeploymentProperties publicDeployment,
                                               WindowCounter authGuardWindowCounter) {
        return new LoginAttemptGuard(properties.getLoginLock(), publicDeployment, authGuardWindowCounter);
    }
}
