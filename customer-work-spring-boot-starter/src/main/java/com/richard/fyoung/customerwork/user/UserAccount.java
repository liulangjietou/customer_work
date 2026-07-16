package com.richard.fyoung.customerwork.user;

import lombok.Getter;

/**
 * 用户账户（充血实体）：客服系统的终端用户身份，承载登录凭据与启停状态。
 *
 * <p>密码以哈希形式（BCrypt）存储，实体只持有 {@code passwordHash}，不接触明文（加密/校验在
 * {@link UserAccountService} 完成，职责边界清晰）。启停是实体自身的状态语义，故 {@link #disable()}
 * 与 {@link #isActive()} 内聚在实体上。</p>
 * @author owlzhangfq@gmail.com
 */
@Getter
public class UserAccount {

    /** 账户状态：启用 / 停用。 */
    public enum Status {
        /** 启用（可登录）。 */
        ACTIVE,
        /** 停用（禁止登录）。 */
        DISABLED
    }

    private final String id;
    private final String username;
    private final String passwordHash;
    private final String nickname;
    private final String phone;
    private final long createdAtMs;

    private volatile Status status;

    private UserAccount(String id, String username, String passwordHash, String nickname,
                        String phone, Status status, long createdAtMs) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.phone = phone;
        this.status = status;
        this.createdAtMs = createdAtMs;
    }

    /** 注册静态工厂：初始 ACTIVE。 */
    public static UserAccount create(String id, String username, String passwordHash, String nickname, String phone) {
        return new UserAccount(id, username, passwordHash, nickname, phone, Status.ACTIVE,
            System.currentTimeMillis());
    }

    /** 停用账户（禁止后续登录）。 */
    public void disable() {
        this.status = Status.DISABLED;
    }

    /** 是否处于启用态。 */
    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    /** 供持久化层从数据源重建（跳过业务语义，仅回填字段）。包级可见。 */
    static UserAccount reconstruct(String id, String username, String passwordHash, String nickname,
                                   String phone, Status status, long createdAtMs) {
        return new UserAccount(id, username, passwordHash, nickname, phone, status, createdAtMs);
    }
}
