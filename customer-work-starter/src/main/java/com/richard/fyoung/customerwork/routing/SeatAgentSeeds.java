package com.richard.fyoung.customerwork.routing;

import java.util.List;
import java.util.Set;

/**
 * 坐席演示种子，与 {@code customer-work-schema.sql} 的 {@code INSERT IGNORE} 保持一致：memory 模式由
 * {@link InMemorySeatAgentStore} 装载，jdbc 模式由建表脚本装载。
 *
 * <p>覆盖退款/物流/投诉/发票多技能、不同负载与在离线状态，便于演示打分与 top-N 推荐。</p>
 * @author owlzhangfq@gmail.com
 */
public final class SeatAgentSeeds {

    private SeatAgentSeeds() {
    }

    /** 演示坐席种子。 */
    public static List<SeatAgent> demoSeats() {
        return List.of(
            new SeatAgent("SEAT-1001", "退款专员-小赵", Set.of("refund", "invoice"), 5, 1, true, "aftersales"),
            new SeatAgent("SEAT-1002", "物流专员-小钱", Set.of("logistics"), 5, 3, true, "logistics"),
            new SeatAgent("SEAT-1003", "投诉专员-小孙", Set.of("complaint", "refund"), 4, 0, true, "complaint"),
            new SeatAgent("SEAT-1004", "综合坐席-小李", Set.of("refund", "logistics", "complaint", "invoice"), 6, 5, true, "general"),
            new SeatAgent("SEAT-1005", "离线坐席-小周", Set.of("refund"), 5, 0, false, "aftersales"));
    }
}
