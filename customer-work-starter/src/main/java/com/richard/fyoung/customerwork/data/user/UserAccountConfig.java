package com.richard.fyoung.customerwork.data.user;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.data.user.mapper.UserMapper;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectLevelBinding;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectLevelResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 用户账户域装配：按 {@code customer-work.user-auth.store-mode} 选择存储实现并装配服务。
 *
 * <p>默认 {@code memory}；{@code jdbc} 落地为 {@link MybatisUserAccountStore}（MyBatis-Plus，复用
 * {@code CustomerWorkPersistenceConfig} 的独立持久化环境）。{@link UserMapper} 用 {@link ObjectProvider}
 * 惰性获取，仅 jdbc 分支取用。两个 Bean 均 {@code @ConditionalOnMissingBean}，下游可覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class UserAccountConfig {

    private static final Logger log = LoggerFactory.getLogger(UserAccountConfig.class);

    private static final String STORE_MODE_JDBC = "jdbc";

    @Bean
    @ConditionalOnMissingBean(UserAccountStore.class)
    public UserAccountStore userAccountStore(CustomerWorkProperties properties, ObjectProvider<UserMapper> mapperProvider) {
        String mode = properties.getUserAuth().getStoreMode();
        if (STORE_MODE_JDBC.equalsIgnoreCase(mode)) {
            log.info("user store: jdbc (MyBatis-Plus 实现, table=cw_user)");
            return new MybatisUserAccountStore(mapperProvider.getObject());
        }
        log.info("user store: memory (进程内，重启不保留，生产建议 store-mode=jdbc)");
        return new InMemoryUserAccountStore();
    }

    @Bean
    @ConditionalOnMissingBean(UserAccountService.class)
    public UserAccountService userAccountService(UserAccountStore userAccountStore,
                                                 CustomerWorkProperties properties,
                                                 ObjectProvider<SubjectLevelResolver> resolverProvider) {
        SubjectLevelResolver resolver = resolverProvider.getIfAvailable();
        return new UserAccountService(userAccountStore,
            properties.getSubjectQuota().getDefaultUserLevel(),
            resolver == null ? null : resolver::evictBinding);
    }

    /**
     * 用户 → 配额等级 的绑定查询实现。
     *
     * <p>无条件装配（不看配额开关）：Bean 存在本身没有开销，而按开关装配会让运行期
     * 打开配额时缺一个 Bean——那种"配置生效了但功能没生效"的状态最难查。</p>
     */
    @Bean
    @ConditionalOnMissingBean(SubjectLevelBinding.class)
    public SubjectLevelBinding userAccountLevelBinding(UserAccountStore userAccountStore) {
        return new UserAccountLevelBinding(userAccountStore);
    }
}
