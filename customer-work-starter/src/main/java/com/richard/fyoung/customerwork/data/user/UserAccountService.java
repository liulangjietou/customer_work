package com.richard.fyoung.customerwork.data.user;

import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 用户账户服务：注册与登录校验。
 *
 * <p>密码加密/校验在本层完成（BCrypt，慢哈希抗爆破），实体只持有哈希、绝不接触明文。注册前置
 * 用户名唯一校验（重名 fast-fail）；登录校验对"用户不存在 / 密码错 / 账户停用"统一返回
 * {@link Optional#empty()}——不向调用方泄露失败的具体原因（避免用户名枚举）。</p>
 * @author owlzhangfq@gmail.com
 */
public class UserAccountService {

    private static final Logger log = LoggerFactory.getLogger(UserAccountService.class);

    private static final String ID_PREFIX = "U-";

    private final UserAccountStore store;
    private final PasswordEncoder passwordEncoder;

    /** 新注册账户落的配额等级（来自 {@code customer-work.subject-quota.default-user-level}）；空表示不写等级。 */
    private final String defaultLevelCode;

    /** 令当前用户已签发的所有 JWT 立即失效。 */
    public long revokeSessions(String userId) {
        UserAccount account = store.findById(userId)
            .orElseThrow(() -> new IllegalStateException("user not found: " + userId));
        long epoch = store.incrementSessionEpoch(userId);
        account.synchronizeSessionEpoch(epoch);
        log.info("user sessions revoked: id={}, sessionEpoch={}", userId, epoch);
        return epoch;
    }

    /** 每次鉴权都与权威账户状态/会话版本比对，旧令牌 fail closed。 */
    public boolean isSessionActive(String userId, Long expectedEpoch) {
        if (userId == null || userId.isBlank() || expectedEpoch == null) {
            return false;
        }
        return store.findById(userId)
            .filter(UserAccount::isActive)
            .map(account -> account.getSessionEpoch() == expectedEpoch)
            .orElse(false);
    }

    /**
     * 在凭据声明的租户内校验会话版本，并在完成后恢复调用线程原有上下文。
     * HTTP、WebSocket 与匿名聊天入口都应调用本方法，避免在租户上下文建立前查询 {@code cw_user}。
     */
    public boolean isSessionActive(String tenantId, String userId, Long expectedEpoch) {
        if (!TenantContext.isValidTenantId(tenantId)) {
            return false;
        }
        return TenantContext.callWith(tenantId, () -> isSessionActive(userId, expectedEpoch));
    }

    /**
     * 等级变更监听（可为 null）：用来让配额侧的绑定缓存立即失效。
     *
     * <p>用 {@link Consumer} 而不是直接持有配额领域的对象，是为了不让账户领域反向依赖它——
     * 账户只需要广播"这个人的等级变了"，谁关心、怎么处理不归它管。</p>
     */
    private final Consumer<String> levelChangeListener;

    public UserAccountService(UserAccountStore store) {
        this(store, null, null);
    }

    public UserAccountService(UserAccountStore store, String defaultLevelCode) {
        this(store, defaultLevelCode, null);
    }

    public UserAccountService(UserAccountStore store, String defaultLevelCode,
                              Consumer<String> levelChangeListener) {
        this.store = store;
        this.passwordEncoder = new BCryptPasswordEncoder();
        this.defaultLevelCode = defaultLevelCode;
        this.levelChangeListener = levelChangeListener;
    }

    /**
     * 注册新账户：用户名唯一，密码 BCrypt 加密后落库。
     *
     * @throws IllegalStateException 用户名已存在
     */
    public UserAccount register(String username, String rawPassword, String nickname, String phone) {
        if (store.findByUsername(username).isPresent()) {
            throw new IllegalStateException("username already exists: " + username);
        }
        String id = ID_PREFIX + UUID.randomUUID();
        String hash = passwordEncoder.encode(rawPassword);
        // 注册即落默认等级：不落的话新用户在等级表里查无此人，只能靠"查不到就用默认档"兜着——
        // 那条兜底路径必须一直留着，运营也永远看不到"这个人现在是哪一档"
        UserAccount account = UserAccount.create(id, username, hash, nickname, phone, defaultLevelCode);
        store.save(account);
        log.info("user registered: id={}, username={}, level={}", id, username, defaultLevelCode);
        return account;
    }

    /**
     * 登录校验：用户名存在、账户启用、密码匹配三者全满足才返回账户，否则统一返回 empty。
     */
    public Optional<UserAccount> verifyLogin(String username, String rawPassword) {
        Optional<UserAccount> found = store.findByUsername(username);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        UserAccount account = found.get();
        if (!account.isActive()) {
            return Optional.empty();
        }
        if (!passwordEncoder.matches(rawPassword, account.getPasswordHash())) {
            return Optional.empty();
        }
        return Optional.of(account);
    }

    public Optional<UserAccount> findById(String id) {
        return store.findById(id);
    }

    /**
     * 更新用户头像：查出账户 → 充血实体自改头像 → 持久化。
     *
     * @throws IllegalStateException 账户不存在
     * @return 已更新头像的账户
     */
    /**
     * 调整用户配额等级：查出账户 → 充血实体自改等级 → 持久化。
     *
     * @param levelCode 目标等级编码；传 null 表示回到默认档
     * @throws IllegalStateException 账户不存在
     * @return 已更新等级的账户
     */
    public UserAccount updateLevel(String userId, String levelCode) {
        UserAccount account = store.findById(userId)
            .orElseThrow(() -> new IllegalStateException("user not found: " + userId));
        account.changeLevel(levelCode);
        store.updateLevel(account.getId(), account.getLevelCode());
        // 同进程改档立即生效：不通知的话这里也要等绑定缓存过期，而缓存本是为跨进程场景设的
        if (levelChangeListener != null) {
            levelChangeListener.accept(userId);
        }
        log.info("user quota level updated: id={}, level={}", userId, levelCode);
        return account;
    }

    public UserAccount updateAvatar(String userId, String avatarUrl) {
        UserAccount account = store.findById(userId)
            .orElseThrow(() -> new IllegalStateException("user not found: " + userId));
        account.changeAvatar(avatarUrl);
        store.updateAvatar(account.getId(), account.getAvatarUrl());
        log.info("user avatar updated: id={}", userId);
        return account;
    }
}
