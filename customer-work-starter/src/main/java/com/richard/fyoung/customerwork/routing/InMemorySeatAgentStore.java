package com.richard.fyoung.customerwork.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内坐席库（默认实现）。
 *
 * <p>用 {@link ConcurrentHashMap} 保证线程安全；构造时加载与 {@code customer-work-schema.sql} 一致的演示坐席种子，
 * 使 memory 模式下无需数据库即可演示分类打分推荐。以 {@code @ConditionalOnMissingBean} 注册，下游声明自己的
 * {@link SeatAgentStore} 即可覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemorySeatAgentStore implements SeatAgentStore {

    private final ConcurrentHashMap<String, SeatAgent> store = new ConcurrentHashMap<>();

    public InMemorySeatAgentStore() {
        for (SeatAgent seed : SeatAgentSeeds.demoSeats()) {
            save(seed);
        }
    }

    @Override
    public List<SeatAgent> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public void save(SeatAgent seat) {
        if (seat == null || seat.getId() == null || seat.getId().isEmpty()) {
            return;
        }
        store.put(seat.getId(), seat);
    }
}
