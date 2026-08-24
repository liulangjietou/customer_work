package com.richard.fyoung.customeradmin.workspace.memory;

import java.time.LocalDateTime;

/**
 * 长期记忆快照：{@code content} 为 MEMORY.md 全文，{@code updateTime} 为权威存储侧的最后更新时间
 * （JDBC 模式取 update_time 列，磁盘模式取文件 mtime）。
 * @author owlzhangfq@gmail.com
 */
public record AgentMemorySnapshot(String content, LocalDateTime updateTime, long version) {

    /** 兼容旧调用点；存量无版本快照按首版处理。 */
    public AgentMemorySnapshot(String content, LocalDateTime updateTime) {
        this(content, updateTime, 1L);
    }
}
