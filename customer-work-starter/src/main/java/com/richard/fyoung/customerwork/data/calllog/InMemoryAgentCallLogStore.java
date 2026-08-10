package com.richard.fyoung.customerwork.data.calllog;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 进程内智能体调用日志存储（默认实现，测试 / 演示用）。
 *
 * <p>{@link ConcurrentHashMap} 承载主记录，{@link AtomicLong} 模拟自增主键；查询/汇总/趋势语义与
 * {@link MybatisAgentCallLogStore} 对齐（趋势分桶用与 SQL {@code DATE_FORMAT} 等价的
 * {@link DateTimeFormatter}，时区取系统默认，与 MySQL {@code FROM_UNIXTIME} 一致）。以
 * {@code @ConditionalOnMissingBean} 注册，下游声明自己的 {@link AgentCallLogStore} Bean 即可覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryAgentCallLogStore implements AgentCallLogStore {

    private final Map<Long, AgentCallRecord> records = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(0);

    @Override
    public AgentCallRecord save(AgentCallRecord record) {
        AgentCallRecord persisted = record.withId(idSeq.incrementAndGet());
        records.put(persisted.id(), persisted);
        return persisted;
    }

    @Override
    public List<AgentCallRecord> findPage(AgentCallLogQuery query) {
        return matched(query)
            .sorted(Comparator.comparingLong(AgentCallRecord::id).reversed())
            .skip(Math.max(query.offset(), 0))
            .limit(Math.max(query.limit(), 0))
            .collect(Collectors.toList());
    }

    @Override
    public long count(AgentCallLogQuery query) {
        return matched(query).count();
    }

    @Override
    public List<AgentCallSegment> findSegments(long callLogId) {
        AgentCallRecord record = records.get(callLogId);
        if (record == null || record.segments() == null) {
            return List.of();
        }
        return record.segments().stream()
            .sorted(Comparator.comparingInt(AgentCallSegment::seq))
            .collect(Collectors.toList());
    }

    @Override
    public boolean delete(long id) {
        return records.remove(id) != null;
    }

    @Override
    public AgentCallLogSummary summary(AgentCallLogQuery query) {
        List<AgentCallRecord> list = matched(query).collect(Collectors.toList());
        if (list.isEmpty()) {
            return AgentCallLogSummary.empty();
        }
        long total = list.size();
        // token 汇总：null 视为 0（与 SQL SUM/AVG 忽略 NULL 的口径略有差异——内存实现仅测试/演示用，
        // 生产走 MybatisAgentCallLogStore，此处保持简单一致即可）
        long totalTokens = list.stream().mapToLong(r -> r.totalTokens() == null ? 0L : r.totalTokens()).sum();
        long inputTokens = list.stream().mapToLong(r -> r.inputTokens() == null ? 0L : r.inputTokens()).sum();
        long cachedTokens = list.stream().mapToLong(r -> r.cachedTokens() == null ? 0L : r.cachedTokens()).sum();
        return new AgentCallLogSummary(
            total,
            list.stream().mapToLong(AgentCallRecord::durationMs).average().orElse(0d),
            list.stream().mapToLong(AgentCallRecord::durationMs).max().orElse(0L),
            list.stream().mapToLong(AgentCallRecord::modelMs).average().orElse(0d),
            list.stream().mapToLong(AgentCallRecord::toolMs).average().orElse(0d),
            list.stream().mapToLong(AgentCallRecord::mcpMs).average().orElse(0d),
            list.stream().mapToLong(AgentCallRecord::skillMs).average().orElse(0d),
            totalTokens,
            (double) totalTokens / total,
            inputTokens,
            cachedTokens);
    }

    @Override
    public List<AgentCallTrendPoint> trend(AgentCallLogQuery query, TrendGranularity granularity) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(granularity.javaPattern())
            .withZone(ZoneId.systemDefault());
        // 分桶保序（升序）：LinkedHashMap + 先按桶标签排序的分组
        Map<String, List<AgentCallRecord>> grouped = matched(query)
            .collect(Collectors.groupingBy(r -> formatter.format(Instant.ofEpochMilli(r.startTimeMs()))));
        return grouped.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> new AgentCallTrendPoint(e.getKey(), e.getValue().size(),
                e.getValue().stream().mapToLong(AgentCallRecord::durationMs).average().orElse(0d),
                e.getValue().stream().mapToLong(r -> r.totalTokens() == null ? 0L : r.totalTokens()).sum()))
            .collect(Collectors.toList());
    }

    /** 应用查询条件（各维度为空即不约束）。 */
    private java.util.stream.Stream<AgentCallRecord> matched(AgentCallLogQuery query) {
        Predicate<AgentCallRecord> p = r -> true;
        if (query.username() != null) {
            p = p.and(r -> query.username().equals(r.username()));
        }
        if (query.agentCode() != null) {
            p = p.and(r -> query.agentCode().equals(r.agentCode()));
        }
        if (query.requestId() != null) {
            p = p.and(r -> query.requestId().equals(r.requestId()));
        }
        if (query.sessionId() != null) {
            p = p.and(r -> query.sessionId().equals(r.sessionId()));
        }
        if (query.startFromMs() != null) {
            p = p.and(r -> r.startTimeMs() >= query.startFromMs());
        }
        if (query.startToMs() != null) {
            p = p.and(r -> r.startTimeMs() <= query.startToMs());
        }
        return records.values().stream().filter(p);
    }
}
