package com.richard.fyoung.customerwork.core.memory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 事实日志持久化对象（贫血数据袋）：与 {@code cw_fact_log} 表一一映射。
 *
 * <p>{@code scopeId} 是记忆分区键（{@code TenantResolver} 从 sessionId 解析），与租户列 {@code tenant_id}
 * 是两个维度——后者由租户拦截器自动填充过滤，不出现在本类里。表是 append-only 的，
 * 故本类没有更新语义，Store 侧只 INSERT。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_fact_log")
public class FactLogDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String scopeId;
    private String fact;
    private Long ts;
}
