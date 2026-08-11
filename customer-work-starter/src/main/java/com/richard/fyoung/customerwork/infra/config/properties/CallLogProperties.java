package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体调用分段耗时统计配置。
 *
 * <p>{@code enabled} 控制 {@code AgentCallTimingMiddleware} 是否采集（默认开，开销极低：仅
 * nanoTime 计时 + 异步落库，不阻塞响应流）；{@code store-mode} 决定 {@code AgentCallLogStore} 的
 * 持久化方式（memory 仅单实例 / jdbc 跨实例，落 cw_agent_call_log + cw_agent_call_segment 两表）。</p>
 */
@Data
public class CallLogProperties {
    /** 是否启用调用分段耗时采集（默认开启）。 */
    private boolean enabled = true;
    /** 存储模式：memory（进程内，默认）| jdbc（跨实例共享）。 */
    private String storeMode = "memory";
}
