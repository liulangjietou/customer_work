package com.richard.fyoung.customeradmin.auth.guard;

import com.richard.fyoung.customeradmin.auth.email.EmailVerificationService;
import com.richard.fyoung.customeradmin.auth.email.EmailVerificationStore;
import com.richard.fyoung.customeradmin.auth.email.InMemoryEmailVerificationStore;
import com.richard.fyoung.customeradmin.auth.email.RedissonEmailVerificationStore;
import com.richard.fyoung.customeradmin.notify.AdminMailSender;
import com.richard.fyoung.customeradmin.publicdeploy.PublicDeploymentProperties;
import com.richard.fyoung.customerwork.infra.counter.InMemoryWindowCounter;
import com.richard.fyoung.customerwork.infra.counter.RedissonWindowCounter;
import com.richard.fyoung.customerwork.infra.counter.WindowCounter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 注册与登录防滥用能力的装配。
 *
 * <p>{@code @EnableConfigurationProperties} 是 {@link RegistrationGuardProperties} 的唯一注册入口
 * （它只有 {@code @ConfigurationProperties}，没有 {@code @Component}），删掉会在启动时报
 * {@code NoSuchBeanDefinitionException}——本项目在批量重构配置类时踩过这个坑。</p>
 *
 * <p>计数器与注册验证码延续原有 Redis 运行期降级策略。登录拼图更严格：仅在启动时
 * 没有 Redisson Bean 才选用进程内存储；一旦选用 Redis，请求期异常直接失败关闭，
 * 防止 challenge/proof 因跨存储切换而复活。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Configuration
@EnableConfigurationProperties({RegistrationGuardProperties.class, LoginCaptchaProperties.class})
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

    /** 转发头只有在直接连接方命中显式可信代理网段时才参与来源 IP 解析。 */
    @Bean
    public ClientIpResolver clientIpResolver(RegistrationGuardProperties properties,
                                             ServerProperties serverProperties) {
        ServerProperties.Tomcat.Remoteip remoteIp = serverProperties.getTomcat().getRemoteip();
        if (serverProperties.getForwardHeadersStrategy() != ServerProperties.ForwardHeadersStrategy.NONE
            || StringUtils.hasText(remoteIp.getRemoteIpHeader())
            || StringUtils.hasText(remoteIp.getProtocolHeader())) {
            throw new IllegalStateException(
                "container forward-header handling must remain disabled; ClientIpResolver is the only trust boundary");
        }
        return new ClientIpResolver(properties);
    }

    @Bean
    public LoginCaptchaStore loginCaptchaStore(ObjectProvider<RedissonClient> redissonProvider,
                                               LoginCaptchaProperties properties) {
        InMemoryLoginCaptchaStore inMemory = new InMemoryLoginCaptchaStore(
            properties.getMaxInMemoryEntries(), java.time.Clock.systemUTC());
        RedissonClient redisson = redissonProvider.getIfAvailable();
        if (redisson == null) {
            log.info("login captcha store uses in-process mode (no RedissonClient at startup)");
            return inMemory;
        }
        return new RedissonLoginCaptchaStore(redisson);
    }

    @Bean
    public LoginCaptchaService loginCaptchaService(LoginCaptchaStore loginCaptchaStore,
                                                   LoginCaptchaProperties properties,
                                                   WindowCounter authGuardWindowCounter) {
        return new LoginCaptchaService(loginCaptchaStore, properties, authGuardWindowCounter);
    }

    /**
     * 邮箱验证码存储：与图形验证码同样的降级方向——Redis 不可用退进程内，而不是放行。
     *
     * <p>降级下多副本会出现"在 A 实例发码、到 B 实例核验"而核验不过的情况，
     * 所以对外实例必须保证 Redis 可用；但那属于部署问题，不该让基础设施抖动
     * 直接把邮箱验证这道准入关掉。</p>
     */
    @Bean
    public EmailVerificationStore emailVerificationStore(ObjectProvider<RedissonClient> redissonProvider) {
        InMemoryEmailVerificationStore inMemory = new InMemoryEmailVerificationStore();
        RedissonClient redisson = redissonProvider.getIfAvailable();
        if (redisson == null) {
            log.info("email verification store falls back to in-process (no RedissonClient)");
            return inMemory;
        }
        return new RedissonEmailVerificationStore(redisson, inMemory);
    }

    @Bean
    public EmailVerificationService emailVerificationService(RegistrationGuardProperties properties,
                                                             EmailVerificationStore emailVerificationStore,
                                                             AdminMailSender adminMailSender,
                                                             WindowCounter authGuardWindowCounter) {
        return new EmailVerificationService(properties, emailVerificationStore, adminMailSender,
            authGuardWindowCounter);
    }

    @Bean
    public RegistrationGuard registrationGuard(RegistrationGuardProperties properties,
                                               PublicDeploymentProperties publicDeployment,
                                               CaptchaService captchaService,
                                               EmailVerificationService emailVerificationService,
                                               WindowCounter authGuardWindowCounter) {
        if (publicDeployment.isEnabled()) {
            log.info("public deployment: registration captcha, email and email verification "
                + "are enforced regardless of config");
        }
        return new RegistrationGuard(properties, publicDeployment, captchaService,
            emailVerificationService, authGuardWindowCounter);
    }

    @Bean
    public LoginAttemptGuard loginAttemptGuard(RegistrationGuardProperties properties,
                                               PublicDeploymentProperties publicDeployment,
                                               WindowCounter authGuardWindowCounter) {
        return new LoginAttemptGuard(properties.getLoginLock(), publicDeployment, authGuardWindowCounter);
    }
}
