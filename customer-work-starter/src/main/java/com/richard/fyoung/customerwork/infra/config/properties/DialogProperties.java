package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话阶段状态机存储配置。
 *
 * <p>决定 {@code DialogStageService} 的会话阶段持久化方式：memory 模式下多实例部署时，
 * 请求被负载均衡到不同实例会导致阶段状态"归零"回 GREETING（动态 Prompt 状态机失效）；
 * jdbc 模式跨实例共享同一份阶段状态。</p>
 */
@Data
public class DialogProperties {
    /** 存储模式：memory（进程内，默认，仅单实例场景适用）| jdbc（跨实例共享）。 */
    private String storeMode = "memory";
}
