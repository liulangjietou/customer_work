package com.richard.fyoung.customeradmin.subjectquota.runtime;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectLevelBinding;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * 后台登录用户 → 配额等级的绑定查询（{@code sys_user.level_code}）。
 *
 * <p>与客服端那份 {@code UserAccountLevelBinding} 是同一个 SPI 的两个实现：一边查 {@code cw_user}，
 * 一边查 {@code sys_user}。解析逻辑不需要知道这个区别——它只问"这个人是哪一档"。</p>
 *
 * <p>查询走 {@link CrossTenantOperations}：限流判定发生在拦截器里，那时租户上下文尚未写入
 * （租户拦截器排在最后执行），带过滤会直接 fail-closed 抛错，把一次限流判定变成一个 500。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class AdminUserLevelBinding implements SubjectLevelBinding {

    private final SysUserMapper userMapper;

    public AdminUserLevelBinding(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public Optional<String> levelCodeOf(String adminUserId) {
        if (adminUserId == null || adminUserId.isBlank()) {
            return Optional.empty();
        }
        try {
            // 只取等级这一列：为了一个档位把整行（含密码哈希）拉出来没有必要。
            // 用字符串列名而不是 lambda：lambda 解析依赖 MyBatis-Plus 的 TableInfo 缓存，
            // 在不启动容器的单测里拿不到，会直接抛 MybatisPlusException
            SysUser user = CrossTenantOperations.execute(() -> userMapper.selectOne(
                new QueryWrapper<SysUser>()
                    .select("level_code")
                    .eq("id", Long.valueOf(adminUserId.trim()))));
            return Optional.ofNullable(user)
                .map(SysUser::getLevelCode)
                .filter(code -> !code.isBlank());
        } catch (NumberFormatException e) {
            // 登录 ID 恒为 sys_user.id（Long），解析不了说明调用方传错了主体，按未绑定处理
            log.error("admin quota level binding got non-numeric user id, code={}, id={}",
                "SQUOTA-ADMIN-BINDING-BAD-ID", adminUserId);
            return Optional.empty();
        } catch (Exception e) {
            // 查不到绑定就走默认档：为一次等级查询失败把用户挡在门外，比放行的代价大得多
            log.error("admin quota level binding query failed, code={}, id={}",
                "SQUOTA-ADMIN-BINDING-FAIL", adminUserId, e);
            return Optional.empty();
        }
    }
}
