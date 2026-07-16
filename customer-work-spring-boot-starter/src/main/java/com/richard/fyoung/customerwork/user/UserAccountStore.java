package com.richard.fyoung.customerwork.user;

import java.util.Optional;

/**
 * 用户账户存储 SPI（持久化扩展点）。
 *
 * <p>默认 {@link InMemoryUserAccountStore}（进程内，离线可测）；生产按
 * {@code customer-work.user-auth.store-mode=jdbc} 切换为 {@link MybatisUserAccountStore}。
 * 下游亦可声明同类型 Bean 整体覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
public interface UserAccountStore {

    /** 保存（新建）账户。 */
    void save(UserAccount account);

    /** 按用户名查（登录 / 重名校验）。 */
    Optional<UserAccount> findByUsername(String username);

    /** 按账户 ID 查。 */
    Optional<UserAccount> findById(String id);
}
