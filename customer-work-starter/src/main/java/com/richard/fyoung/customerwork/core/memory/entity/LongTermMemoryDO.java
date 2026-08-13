package com.richard.fyoung.customerwork.core.memory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 长期记忆事实持久化对象（贫血数据袋）：与 {@code cw_long_term_memory} 表一一映射。
 *
 * <p>{@code scopeId} 是记忆分区键（{@code TenantResolver} 从 sessionId 解析），与租户列 {@code tenant_id}
 * 是两个维度——后者由租户拦截器自动填充过滤，不出现在本类里。{@code scopeHash} 是
 * {@code scopeId + fact} 的 SHA-256，用于同租户内去重（TEXT 列无法直接建唯一索引）。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_long_term_memory")
public class LongTermMemoryDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String scopeId;
    private String fact;
    private String scopeHash;
    private Long createdAtMs;
}
