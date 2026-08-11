package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单智能分配配置。默认关闭。
 *
 * <p>转人工时对工单做一次性 LLM 分类（类目/技能/优先级/情绪）+ 确定性打分器给候选坐席打分，把 top-N
 * 推荐写入工单供坐席工作台展示——<b>仅推荐、不自动派单</b>，人工点选后仍复用现有 claim 流程。任一步失败
 * fail-open（退回现有拉取模型：无推荐、坐席照常手动接单），不阻断转人工。</p>
 */
@Data
public class RoutingProperties {
    /** 转人工时自动做工单分类 + 坐席打分推荐（默认关；不自动派单，仅写入推荐供坐席点选）。 */
    private boolean assignEnabled = false;
    /** 坐席库存储模式：memory（进程内带演示种子，默认）| jdbc（跨实例共享）。 */
    private String seatStoreMode = "memory";
    /** 推荐坐席 top-N（默认 3）。 */
    private int topN = 3;
    /** 单次工单分类 LLM 调用超时（秒）。 */
    private long classifyTimeoutSeconds = 30;
}
