package com.richard.fyoung.customerwork.data.user;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.richard.fyoung.customerwork.data.user.entity.UserDO;
import com.richard.fyoung.customerwork.data.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * MyBatis-Plus 用户账户存储（{@code customer-work.user-auth.store-mode=jdbc} 时启用）。
 *
 * <p>把账户写入 {@code cw_user} 表（username 唯一键）。{@link #save} 为纯 INSERT（账户创建无 upsert 语义），
 * 失败抛 {@link IllegalStateException}（对齐旧实现，注册链路需感知重名/写入失败）；读取失败仅记 error 返回空。
 * 异常一律 {@code catch(Exception)}（HikariPool 初始化异常是 RuntimeException，非 SQLException 子类）。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisUserAccountStore implements UserAccountStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisUserAccountStore.class);

    private final UserMapper mapper;

    public MybatisUserAccountStore(UserMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(UserAccount account) {
        if (account == null || account.getId() == null) {
            return;
        }
        try {
            mapper.insert(toDO(account));
        } catch (Exception e) {
            log.error("user save failed, code={}, id={}", "USER-STORE-SAVE-FAIL", account.getId(), e);
            throw new IllegalStateException("failed to save user account: " + account.getId(), e);
        }
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        try {
            UserDO row = mapper.selectOne(new QueryWrapper<UserDO>().eq("username", username));
            return row == null ? Optional.empty() : Optional.of(toDomain(row));
        } catch (Exception e) {
            log.error("user query failed, code={}, param={}", "USER-STORE-FINDBYNAME-FAIL", username, e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<UserAccount> findById(String id) {
        try {
            UserDO row = mapper.selectById(id);
            return row == null ? Optional.empty() : Optional.of(toDomain(row));
        } catch (Exception e) {
            log.error("user query failed, code={}, param={}", "USER-STORE-FINDBYID-FAIL", id, e);
            return Optional.empty();
        }
    }

    @Override
    public void updateAvatar(String id, String avatarUrl) {
        if (id == null) {
            return;
        }
        try {
            UserDO row = new UserDO();
            row.setId(id);
            row.setAvatarUrl(avatarUrl);
            // updateById 默认仅更新非空字段（FieldStrategy NOT_NULL），故只改 avatar_url 一列
            mapper.updateById(row);
        } catch (Exception e) {
            log.error("user avatar update failed, code={}, id={}", "USER-STORE-UPDATE-AVATAR-FAIL", id, e);
            throw new IllegalStateException("failed to update user avatar: " + id, e);
        }
    }

    @Override
    public void updateLevel(String id, String levelCode) {
        if (id == null) {
            return;
        }
        try {
            // 必须走 UpdateWrapper 显式 set：updateById 只更新非空字段，用它把等级置空会静默失败，
            // 表现为"后台取消了特批额度、线上却一直按特批放行"
            mapper.update(null, new LambdaUpdateWrapper<UserDO>()
                .set(UserDO::getLevelCode, levelCode)
                .eq(UserDO::getId, id));
        } catch (Exception e) {
            log.error("user level update failed, code={}, id={}", "USER-STORE-UPDATE-LEVEL-FAIL", id, e);
            throw new IllegalStateException("failed to update user level: " + id, e);
        }
    }

    @Override
    public long incrementSessionEpoch(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("user id is required");
        }
        try {
            if (mapper.incrementSessionEpoch(id) != 1) {
                throw new IllegalStateException("user not found: " + id);
            }
            Long epoch = mapper.selectSessionEpoch(id);
            if (epoch == null) {
                throw new IllegalStateException("user session epoch not found: " + id);
            }
            return epoch;
        } catch (Exception e) {
            log.error("user sessions revoke failed, code={}, id={}",
                "USER-STORE-REVOKE-FAIL", id, e);
            throw new IllegalStateException("failed to revoke user sessions: " + id, e);
        }
    }

    private UserAccount toDomain(UserDO row) {
        return UserAccount.reconstruct(
            row.getId(),
            row.getUsername(),
            row.getPasswordHash(),
            row.getNickname(),
            row.getPhone(),
            UserAccount.Status.valueOf(row.getStatus()),
            row.getCreatedAtMs(),
            row.getAvatarUrl(),
            row.getLevelCode(),
            row.getTenantId(),
            row.getSessionEpoch() == null ? 0L : row.getSessionEpoch());
    }

    private UserDO toDO(UserAccount account) {
        UserDO row = new UserDO();
        row.setId(account.getId());
        row.setTenantId(account.getTenantId());
        row.setUsername(account.getUsername());
        row.setPasswordHash(account.getPasswordHash());
        row.setNickname(account.getNickname());
        row.setPhone(account.getPhone());
        row.setStatus(account.getStatus().name());
        row.setCreatedAtMs(account.getCreatedAtMs());
        row.setAvatarUrl(account.getAvatarUrl());
        row.setLevelCode(account.getLevelCode());
        row.setSessionEpoch(account.getSessionEpoch());
        return row;
    }
}
