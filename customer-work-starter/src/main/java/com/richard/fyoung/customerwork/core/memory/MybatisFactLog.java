package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.core.memory.entity.FactLogDO;
import com.richard.fyoung.customerwork.core.memory.mapper.FactLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MyBatis-Plus 事实日志（默认实现：{@code customer-work.fact-log.store-mode=jdbc} 时装配）。
 *
 * <p>把 L3 事实流水结构化写入 {@code cw_fact_log} 表，取代按分区分文件的 JSONL 落盘
 * （{@link FileFactLog}）——多副本部署时各副本共享同一份流水，也不再随容器销毁而丢失。
 * append-only 语义靠"只 INSERT、不 UPDATE/DELETE"保证，自增 {@code id} 即写入顺序。</p>
 *
 * <p>读取带上限（{@code customer-work.fact-log.read-limit}）：事实日志只增不减，不封顶会把整表拉进内存。
 * 超限时保留<b>最近</b> N 条而非最早 N 条——统计与复盘关心的都是近期事实。</p>
 *
 * <p>全部方法失败只记 error 不抛异常，与 {@link FactLog} 约定一致。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisFactLog implements FactLog {

    private static final Logger log = LoggerFactory.getLogger(MybatisFactLog.class);

    private final FactLogMapper mapper;
    private final boolean enabled;
    private final int readLimit;

    public MybatisFactLog(FactLogMapper mapper, boolean enabled, int readLimit) {
        this.mapper = mapper;
        this.enabled = enabled;
        this.readLimit = readLimit > 0 ? readLimit : 1;
    }

    @Override
    public void append(String scopeId, String fact) {
        if (!enabled || fact == null || fact.isBlank()) {
            return;
        }
        try {
            FactLogDO record = new FactLogDO();
            record.setScopeId(scopeId == null ? "default" : scopeId);
            record.setFact(fact.trim());
            record.setTs(System.currentTimeMillis());
            mapper.insert(record);
        } catch (Exception e) {
            log.error("append fact to db failed, code={}, scopeId={}", "FACT-LOG-APPEND-FAIL", scopeId, e);
        }
    }

    @Override
    public List<String> read(String scopeId) {
        List<String> facts = new ArrayList<>();
        for (FactLogDO row : selectInWriteOrder(scopeId)) {
            facts.add(row.getFact());
        }
        return facts;
    }

    @Override
    public List<FactRecord> readRecords(String scopeId) {
        List<FactRecord> records = new ArrayList<>();
        for (FactLogDO row : selectInWriteOrder(scopeId)) {
            records.add(new FactRecord(row.getTs() == null ? 0L : row.getTs(), row.getScopeId(), row.getFact()));
        }
        return records;
    }

    /** 取最近 readLimit 条并反转为写入顺序；失败退化为空列表（读不到事实不该打断调用方）。 */
    private List<FactLogDO> selectInWriteOrder(String scopeId) {
        try {
            List<FactLogDO> rows = new ArrayList<>(
                mapper.selectRecent(scopeId == null ? "default" : scopeId, readLimit));
            Collections.reverse(rows);
            return rows;
        } catch (Exception e) {
            log.error("read facts from db failed, code={}, scopeId={}", "FACT-LOG-READ-FAIL", scopeId, e);
            return List.of();
        }
    }
}
