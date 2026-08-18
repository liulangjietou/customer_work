package com.richard.fyoung.customerwork.data.user;

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

    /** 更新账户头像 URL（按 ID 定向更新单列，不触碰其它字段）。 */
    void updateAvatar(String id, String avatarUrl);

    /**
     * 更新账户配额等级（按 ID 定向更新单列）。
     *
     * <p>传 null 表示回到默认档。实现须真的把列写成 NULL——MyBatis-Plus 默认只更新非空字段，
     * 照抄 {@link #updateAvatar} 的写法会让"取消特批额度"这个操作静默失效。</p>
     */
    void updateLevel(String id, String levelCode);
}
