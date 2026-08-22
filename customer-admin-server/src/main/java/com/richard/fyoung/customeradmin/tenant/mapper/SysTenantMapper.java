package com.richard.fyoung.customeradmin.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.tenant.entity.SysTenant;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 租户主数据 Mapper。
 * @author owlzhangfq@gmail.com
 */
public interface SysTenantMapper extends BaseMapper<SysTenant> {

    /** 状态与访问版本一次更新，确保冻结命令不会留下“状态已变、版本未变”的窗口。 */
    @Update("UPDATE sys_tenant SET status = #{status}, access_epoch = access_epoch + 1, update_time = NOW() "
        + "WHERE id = #{id} AND deleted = 0")
    int updateStatusAndIncrementAccessEpoch(@Param("id") Long id, @Param("status") String status);

    /** 主动撤权或到期时间变化时仅递增访问版本。 */
    @Update("UPDATE sys_tenant SET access_epoch = access_epoch + 1, update_time = NOW() "
        + "WHERE id = #{id} AND deleted = 0")
    int incrementAccessEpoch(@Param("id") Long id);
}
