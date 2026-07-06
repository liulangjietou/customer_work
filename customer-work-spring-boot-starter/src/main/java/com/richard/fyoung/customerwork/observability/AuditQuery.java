package com.richard.fyoung.customerwork.observability;

import java.util.List;

/**
 * 审计查询 SPI（与写入侧 {@link AuditSink} 做接口隔离）。
 *
 * <p>只有能被检索的落地实现才实现本接口——{@link JdbcAuditSink} 实现，可按会话回溯审计轨迹；
 * 默认的 {@link LoggingAuditSink}（写日志）无法结构化查询，故不实现。故障诊断链路以
 * {@code ObjectProvider<AuditQuery>} 可选注入本接口，缺失时优雅降级（不返回审计段而非报错）。</p>
 * @author owlzhangfq@gmail.com
 */
public interface AuditQuery {

    /**
     * 按会话查询最近的审计记录（时间倒序）。
     *
     * @param sessionId 会话 ID（内部据此匹配 {@code agent_name = CustomerServiceAgent-<sessionId>}）
     * @param limit     返回条数上限（必须 &gt; 0）
     * @return 最近的审计记录，时间倒序；无记录返回空列表
     */
    List<AuditRecord> queryBySession(String sessionId, int limit);
}
