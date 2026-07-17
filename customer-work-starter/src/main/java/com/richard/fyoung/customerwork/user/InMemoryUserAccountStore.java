package com.richard.fyoung.customerwork.user;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内用户账户存储（默认实现，测试 / 演示用）。
 *
 * <p>{@link ConcurrentHashMap} 保证线程安全，用户名唯一以 username 维度索引。以
 * {@code @ConditionalOnMissingBean} 注册，下游声明自己的 {@link UserAccountStore} Bean 即可覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryUserAccountStore implements UserAccountStore {

    private final ConcurrentHashMap<String, UserAccount> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UserAccount> byUsername = new ConcurrentHashMap<>();

    @Override
    public void save(UserAccount account) {
        if (account == null || account.getId() == null) {
            return;
        }
        byId.put(account.getId(), account);
        byUsername.put(account.getUsername(), account);
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        return Optional.ofNullable(byUsername.get(username));
    }

    @Override
    public Optional<UserAccount> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public void updateAvatar(String id, String avatarUrl) {
        UserAccount account = byId.get(id);
        if (account != null) {
            account.changeAvatar(avatarUrl);
        }
    }
}
