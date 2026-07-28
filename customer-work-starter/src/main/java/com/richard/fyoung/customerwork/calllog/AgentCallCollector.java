package com.richard.fyoung.customerwork.calllog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次智能体调用的请求级采集器（可变，线程安全）。
 *
 * <p>由 {@code AgentCallTimingMiddleware#onAgent} 在调用开始时创建并放入
 * {@link io.agentscope.core.agent.RuntimeContext}，同一次 call 全程共享——onModelCall / onActing
 * 从 ctx 取到同一实例往里追加分段。工具批量执行时 hook 会有线程跳变，故分段列表用同步包装，
 * {@code seq} 用 {@link AtomicInteger} 自增，保证并发追加安全。</p>
 * @author owlzhangfq@gmail.com
 */
public class AgentCallCollector {

    /** 调用开始墙上时钟（毫秒），组装记录时作为 startTime。 */
    private final long startTimeMs = System.currentTimeMillis();
    private final List<AgentCallSegment> segments = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger seq = new AtomicInteger(0);

    /** 智能体最终回答（从 onAgent 事件流的 AgentResultEvent 采集，最后一个 AGENT_RESULT 为准）。 */
    private volatile String answer;

    /**
     * 追加一段耗时明细。
     *
     * @param kind         分段类别
     * @param name         分段名称（模型名 / 工具名）
     * @param segStartMs   分段开始墙上时钟（毫秒）
     * @param segStartNano 分段开始的 {@code System.nanoTime()}（用于高精度算耗时）
     * @param success      是否成功
     * @param errorMsg     失败原因
     * @param inputTokens     输入 token（仅 MODEL 段有值，缺失传 null）
     * @param outputTokens    输出 token（仅 MODEL 段有值，缺失传 null）
     * @param cachedTokens    命中缓存的输入 token（inputTokens 的子集，缺失传 null）
     * @param modelReportedMs 模型自报耗时（毫秒，缺失传 null）
     */
    public void addSegment(AgentCallKind kind, String name, long segStartMs, long segStartNano,
                           boolean success, String errorMsg, Long inputTokens, Long outputTokens,
                           Long cachedTokens, Long modelReportedMs) {
        long durationMs = Math.max(0L, (System.nanoTime() - segStartNano) / 1_000_000L);
        segments.add(new AgentCallSegment(seq.incrementAndGet(), kind, name, segStartMs, durationMs,
            success, errorMsg, inputTokens, outputTokens, cachedTokens, modelReportedMs));
    }

    /** 设置最终回答（可被后续 AGENT_RESULT 覆盖为最新全文）。 */
    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public long startTimeMs() {
        return startTimeMs;
    }

    /**
     * 组装为不可变记录：按分段类别汇总四类耗时与分段数，填入调用方元数据与整体成败。
     *
     * @param requestId   请求 ID
     * @param userId      用户 ID
     * @param username    用户名
     * @param agentCode   智能体编码
     * @param agentName   智能体名称
     * @param sessionId   会话 ID
     * @param sessionType 会话类型
     * @param question    用户问题
     * @param endTimeMs   调用结束时间戳（毫秒）
     * @param success     整次调用是否成功
     * @param errorMsg    失败原因
     */
    public AgentCallRecord toRecord(String requestId, String userId, String username,
                                    String agentCode, String agentName, String sessionId,
                                    AgentCallSessionType sessionType, String question,
                                    long endTimeMs, boolean success, String errorMsg) {
        // 快照分段（同步列表，拷贝出来再统计，避免统计期间被并发修改）
        List<AgentCallSegment> snapshot = new ArrayList<>(segments);
        long modelMs = 0L;
        long toolMs = 0L;
        long mcpMs = 0L;
        long skillMs = 0L;
        // 请求级 token 汇总：各段 usage 求和；一段都没采到（全 null）则整体置 null（区分"未采到"与"0 token"，
        // 与 admin 审计模块 totalUsage 的空判语义一致）
        Long inputTokens = null;
        Long outputTokens = null;
        // cachedTokens 是 inputTokens 的子集（命中 prompt 缓存的那部分），不参与 totalTokens 计算，
        // 否则会把缓存量重复计一遍；它单独留存是为了成本核算（缓存读通常只按 1/10 计价）与命中率观测
        Long cachedTokens = null;
        Long modelReportedMs = null;
        for (AgentCallSegment s : snapshot) {
            switch (s.kind()) {
                case MODEL -> modelMs += s.durationMs();
                case TOOL -> toolMs += s.durationMs();
                case MCP -> mcpMs += s.durationMs();
                case SKILL -> skillMs += s.durationMs();
            }
            if (s.inputTokens() != null) {
                inputTokens = (inputTokens == null ? 0L : inputTokens) + s.inputTokens();
            }
            if (s.outputTokens() != null) {
                outputTokens = (outputTokens == null ? 0L : outputTokens) + s.outputTokens();
            }
            if (s.cachedTokens() != null) {
                cachedTokens = (cachedTokens == null ? 0L : cachedTokens) + s.cachedTokens();
            }
            if (s.modelReportedMs() != null) {
                modelReportedMs = (modelReportedMs == null ? 0L : modelReportedMs) + s.modelReportedMs();
            }
        }
        Long totalTokens = (inputTokens == null && outputTokens == null) ? null
            : (inputTokens == null ? 0L : inputTokens) + (outputTokens == null ? 0L : outputTokens);
        long durationMs = Math.max(0L, endTimeMs - startTimeMs);
        return new AgentCallRecord(0L, requestId, userId, username, agentCode, agentName, sessionId,
            sessionType, question, answer, startTimeMs, endTimeMs, durationMs,
            modelMs, toolMs, mcpMs, skillMs, snapshot.size(), inputTokens, outputTokens, totalTokens,
            cachedTokens, modelReportedMs, success, errorMsg, snapshot);
    }
}
