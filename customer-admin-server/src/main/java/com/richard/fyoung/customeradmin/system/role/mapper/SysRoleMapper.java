package com.richard.fyoung.customeradmin.system.role.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 角色 Mapper。
 *
 * <p>下面两个方法专门绕开 MyBatis-Plus 的逻辑删除过滤：{@code sys_role.uk_sys_role_tenant_code} 是
 * <b>不含 deleted 列</b>的纯数据库唯一约束，被软删除的行仍然占着编码，而 {@code LambdaQueryWrapper}
 * 会自动追加 {@code AND deleted=0} 从而查不到它——必须用原生 SQL 才能看见/复活。手法与
 * {@code AiKnowledgeBaseMapper#selectDeletedByName}/{@code reviveDeleted} 完全一致。</p>
 * @author owlzhangfq@gmail.com
 */
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /**
     * 按租户与编码查一条<b>已被软删除</b>的角色行（{@code deleted=1}）。租户条件必须显式保留，
     * 与数据库的租户内唯一约束同口径；原生 {@code @Select} 不会被自动追加
     * {@code AND deleted=0}，因此能看见被逻辑删除、但仍占着唯一索引的那一行。
     */
    @Select("SELECT * FROM sys_role WHERE tenant_id = #{tenantId} "
        + "AND role_code = #{roleCode} AND deleted = 1 LIMIT 1")
    SysRole selectDeletedByRoleCode(@Param("tenantId") String tenantId,
                                    @Param("roleCode") String roleCode);

    /**
     * “复活”一个被软删除的角色行：只把 {@code deleted} 置回 0，其余业务字段由调用方随后用
     * {@code updateById} 按新的保存请求整体覆盖（复活后该行对 MyBatis-Plus 重新可见）。
     * 同样用原生 {@code @Update} 才能命中当前 {@code deleted=1} 的目标行，并显式约束归属租户。
     */
    @Update("UPDATE sys_role SET deleted = 0 WHERE id = #{id} "
        + "AND tenant_id = #{tenantId} AND deleted = 1")
    int reviveDeleted(@Param("id") Long id, @Param("tenantId") String tenantId);
}
