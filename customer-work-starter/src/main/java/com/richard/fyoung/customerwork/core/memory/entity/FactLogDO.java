package com.richard.fyoung.customerwork.core.memory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 事实日志持久化对象（贫血数据袋）：与 {@code cw_fact_log} 表一一映射。
 *
 * <p>{@code scopeId} 是 {@code MemorySubjectResolver} 生成的主体记忆分区键，与租户列
 * {@code tenant_id} 是两个维度——后者由租户拦截器自动填充过滤，不出现在本类里。
 * 常规业务链路只追加；隐私擦除与保留策略由独立治理链路执行。</p>
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
