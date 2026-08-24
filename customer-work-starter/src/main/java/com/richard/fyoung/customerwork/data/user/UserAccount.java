package com.richard.fyoung.customerwork.data.user;

import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
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
    /** 数据库保存的权威租户编码，用于签发外部凭据与构造持久命名空间。 */
    private final String tenantId;

    private volatile Status status;
    /** 头像访问 URL（相对路径，可为空——注册时无头像，上传后回填）。 */
    private volatile String avatarUrl;

    /**
     * 配额等级编码（可为空——空表示走配置里的默认档）。
     *
     * <p>放在账户实体上而不是另建一张绑定表：等级是"这个账户能用多少"的属性，
     * 与启停、头像同属账户自身状态，拆出去只会让每次限流判定多一次跨表查询。</p>
     */
    private volatile String levelCode;
    /** 用户会话撤销版本；任何全端退出/停用都必须单调递增。 */
    private volatile long sessionEpoch;

    private UserAccount(String id, String username, String passwordHash, String nickname,
                        String phone, Status status, long createdAtMs, String avatarUrl,
                        String levelCode, String tenantId, long sessionEpoch) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.phone = phone;
        this.status = status;
        this.createdAtMs = createdAtMs;
        this.avatarUrl = avatarUrl;
        this.levelCode = levelCode;
        this.tenantId = tenantId;
        this.sessionEpoch = sessionEpoch;
    }

    /** 注册静态工厂：初始 ACTIVE，无头像，等级留空（= 走配置默认档）。 */
    public static UserAccount create(String id, String username, String passwordHash, String nickname, String phone) {
        return create(id, username, passwordHash, nickname, phone, null);
    }

    /** 注册静态工厂（指定配额等级）：注册链路按配置的默认等级建号，见 {@link UserAccountService#register}。 */
    public static UserAccount create(String id, String username, String passwordHash, String nickname,
                                     String phone, String levelCode) {
        return new UserAccount(id, username, passwordHash, nickname, phone, Status.ACTIVE,
            System.currentTimeMillis(), null, levelCode, TenantContext.get(), 0L);
    }

    /** 停用账户（禁止后续登录）。 */
    public void disable() {
        this.status = Status.DISABLED;
        revokeSessions();
    }

    /** 是否处于启用态。 */
    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    /** 更换头像（上传成功后回填访问 URL；传 null 视为清空头像）。头像是账户自身的展示属性，故内聚在实体上。 */
    public void changeAvatar(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    /** 调整配额等级（后台改档）；传 null 视为回到默认档。 */
    public void changeLevel(String levelCode) {
        this.levelCode = levelCode;
    }

    /** 撤销所有已签发令牌；返回新版本供持久化层校验。 */
    public synchronized long revokeSessions() {
        return ++sessionEpoch;
    }

    /** 持久化层完成原子递增后回填权威版本。 */
    void synchronizeSessionEpoch(long epoch) {
        if (epoch < sessionEpoch) {
            throw new IllegalArgumentException("session epoch must not move backwards");
        }
        this.sessionEpoch = epoch;
    }

    /** 供持久化层从数据源重建（跳过业务语义，仅回填字段）。包级可见。 */
    static UserAccount reconstruct(String id, String username, String passwordHash, String nickname,
                                   String phone, Status status, long createdAtMs, String avatarUrl,
                                   String levelCode, String tenantId, long sessionEpoch) {
        return new UserAccount(id, username, passwordHash, nickname, phone, status, createdAtMs,
            avatarUrl, levelCode, tenantId, sessionEpoch);
    }
}
