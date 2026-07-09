package com.richard.fyoung.customeradmin.system.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 后台用户 Mapper。
 * @author owlzhangfq@gmail.com
 */
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 按用户名查，不受 MyBatis-Plus 逻辑删除自动拼接的 {@code deleted=0} 过滤影响（可命中已被软删除的行）。
     *
     * <p>逻辑删除的 {@code deleted=0} 过滤只会自动拼进 MyBatis-Plus 自己注入的 CRUD 方法（如 selectOne/selectList/
     * update 的 Wrapper 查询），不会影响本接口这种用 {@code @Select} 写的原生 SQL，所以不需要任何旁路注解。
     * {@code sys_user.uk_sys_user_username} 是纯数据库层的唯一约束，不包含 {@code deleted}，故软删除后该用户名仍被
     * 占住。{@link com.richard.fyoung.customeradmin.auth.service.AuthService#findOrCreateLdapUser} 需要用它判定
     * “该用户名是真不存在还是被软删除了”，后者要“复活”而不是盲目 INSERT。</p>
     */
    @Select("SELECT * FROM sys_user WHERE username = #{username} LIMIT 1")
    SysUser selectByUsernameIgnoreLogicDelete(@Param("username") String username);

    /**
     * “复活”一个已被软删除的用户行（重新用于 LDAP 自动开户）。同上：本接口用原生 {@code @Update} SQL，
     * 不会被 MyBatis-Plus 自动追加 {@code AND deleted=0}，能正常命中当前 deleted=1 的目标行。
     */
    @Update("UPDATE sys_user SET deleted = 0, status = 1, login_type = 'LDAP', nickname = #{username}, "
        + "password = NULL, update_time = NOW() WHERE id = #{id}")
    int reviveDeletedUser(@Param("id") Long id, @Param("username") String username);
}
