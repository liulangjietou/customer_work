package com.richard.fyoung.customerwork.core.support;

import com.richard.fyoung.customerwork.core.memory.FactLog;
import com.richard.fyoung.customerwork.core.memory.FactRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内事实日志测试替身：按分区累积事实，供断言"哪些事实被沉淀了"。
 *
 * <p>生产侧刻意<b>没有</b>进程内 / 文件实现（{@code MybatisFactLog} 落库，缺持久化环境时是
 * {@code NoOpFactLog}），故这类断言需要一个只存在于测试的实现。真实落库行为由
 * {@code MybatisFactLogTest} 对着 MySQL 验证。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryTestFactLog implements FactLog {

    private final Map<String, List<FactRecord>> byScope = new ConcurrentHashMap<>();
    private final boolean enabled;

    public InMemoryTestFactLog() {
        this(true);
    }

    /** @param enabled false 时全部丢弃，模拟 {@code fact-log.enabled=false}。 */
    public InMemoryTestFactLog(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void append(String scopeId, String fact) {
        if (!enabled || fact == null || fact.isBlank()) {
            return;
        }
        byScope.computeIfAbsent(scopeId, k -> new ArrayList<>())
            .add(new FactRecord(System.currentTimeMillis(), scopeId, fact.trim()));
    }

    /**
     * 以指定时间戳直接塞入一条事实，供按时间窗聚合的用例构造样本
     * （{@link #append} 只能用当前时间，测不了"窗口外应被过滤"）。
     */
    public void seed(long ts, String scopeId, String fact) {
        byScope.computeIfAbsent(scopeId, k -> new ArrayList<>()).add(new FactRecord(ts, scopeId, fact));
    }

    @Override
    public List<String> read(String scopeId) {
        return readRecords(scopeId).stream().map(FactRecord::fact).toList();
    }

    @Override
    public List<FactRecord> readRecords(String scopeId) {
        return List.copyOf(byScope.getOrDefault(scopeId, List.of()));
    }
}
