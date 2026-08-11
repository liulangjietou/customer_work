package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 多 Agent 编排配置。 */
@Data
public class MultiAgentProperties {
    /** 是否启用多 Agent 编排端点。 */
    private boolean enabled = true;
    /** 编排模式：fanout（并行多专家聚合）| sequential（流水串行细化）。 */
    private String mode = "fanout";
    /** 每个专家 Agent 的 ReAct 最大轮次。 */
    private int maxIters = 6;
    /** 并行 fanout 的最大并发度（同时在跑的专家数上限，&lt;=0 视为 1）。 */
    private int maxConcurrency = 8;
    /** 单个专家调用超时（秒）：超时按错误隔离，不拖垮整体并行。 */
    private long timeoutSeconds = 60;
    /** 智能路由：先用分诊器判断意图，只把问题发给相关专家（省 token / 更准）；关则广播全部专家。 */
    private boolean routingEnabled = true;
    /** 规则快车道：在 LLM 分诊前先用关键词规则命中确定意图（命中即直路由，省一次模型调用、提准降延迟）。 */
    private boolean fastRouteEnabled = true;
    /** reduce 归纳：fanout 后用归纳器把多专家结论二次合成统一口径回复；关则直接拼接各专家结论。 */
    private boolean reduceEnabled = true;
}
