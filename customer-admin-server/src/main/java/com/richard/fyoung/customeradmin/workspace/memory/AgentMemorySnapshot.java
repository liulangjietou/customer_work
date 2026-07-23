package com.richard.fyoung.customeradmin.workspace.memory;

import java.time.LocalDateTime;

/**
 * 长期记忆快照：{@code content} 为 MEMORY.md 全文，{@code updateTime} 为权威存储侧的最后更新时间
 * （JDBC 模式取 update_time 列，磁盘模式取文件 mtime）。
 * @author owlzhangfq@gmail.com
 */
public record AgentMemorySnapshot(String content, LocalDateTime updateTime) {
}
