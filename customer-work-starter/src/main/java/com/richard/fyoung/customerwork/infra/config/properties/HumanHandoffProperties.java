package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人机切换工单存储配置。
 *
 * <p>决定 {@code HandoffService} 的工单持久化方式：memory 模式下多实例部署时，坐席在实例 A
 * 接单、坐席工作台轮询落到实例 B 会查不到最新状态；jdbc 模式跨实例共享同一份工单状态。</p>
 */
@Data
public class HumanHandoffProperties {
    /** 存储模式：memory（进程内，默认，仅单实例场景适用）| jdbc（跨实例共享）。 */
    private String storeMode = "memory";
    /** SLA 告警：PENDING（无人接单）超过该秒数即告警；&lt;=0 禁用。 */
    private long slaPendingSeconds = 0;
    /** SLA 告警：CLAIMED（接单未结案）超过该秒数即告警；&lt;=0 禁用。 */
    private long slaClaimedSeconds = 0;
}
