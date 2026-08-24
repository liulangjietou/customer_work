package com.richard.fyoung.customeradmin.system.log.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.richard.fyoung.customeradmin.system.log.entity.SysOperationLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 操作日志 Mapper（MyBatis-Plus BaseMapper 提供全部基础 CRUD，无需手写 SQL）。
 * @author owlzhangfq@gmail.com
 */
public interface OperationLogMapper extends BaseMapper<SysOperationLog> {

    /** 可在异步完成线程补写终态；id + eventId 双重绑定，且只允许 STARTED 单向推进。 */
    @InterceptorIgnore(tenantLine = "1")
    @Update("""
        UPDATE sys_operation_log
        SET result = #{result}, error_msg = #{errorMsg}, audit_status = 'COMPLETED'
        WHERE id = #{id} AND event_id = #{eventId} AND audit_status = 'STARTED'
        """)
    int completeAudit(@Param("id") Long id, @Param("eventId") String eventId,
                      @Param("result") int result, @Param("errorMsg") String errorMsg);
}
