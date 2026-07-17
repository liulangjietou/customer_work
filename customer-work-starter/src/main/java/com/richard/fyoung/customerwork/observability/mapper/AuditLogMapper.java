package com.richard.fyoung.customerwork.observability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.observability.entity.AuditLogDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 审计轨迹 Mapper。写入走 {@link BaseMapper#insert}；按会话（agent_name 后缀 LIKE）查询在
 * {@code AuditLogMapper.xml} 中手写。
 * @author owlzhangfq@gmail.com
 */
public interface AuditLogMapper extends BaseMapper<AuditLogDO> {

    /** 按会话查询：agent_name LIKE pattern（ESCAPE '\\'），按记录时间倒序取最近 limit 条。 */
    List<AuditLogDO> queryBySession(@Param("pattern") String pattern, @Param("limit") int limit);
}
