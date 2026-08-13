package com.richard.fyoung.customerwork.core.memory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * Harness 分层记忆持久化对象（贫血数据袋）：与 {@code cw_harness_memory} 表一一映射。
 *
 * <p>{@code scopeHash} 是 {@code scopeId} 的 SHA-256：workspace 路径可能很长，
 * 直接对 512 字节的 VARCHAR 建唯一索引会撞上 InnoDB 索引长度上限。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_harness_memory")
public class HarnessMemoryDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String scopeId;
    private String scopeHash;
    private String content;
    private Long updatedAtMs;
}
