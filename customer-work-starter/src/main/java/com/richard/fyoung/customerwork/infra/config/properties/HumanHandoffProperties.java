package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史三态人机切换配置（仅兼容旧配置绑定）。
 *
 * <p>P1-03 起生产统一读取 {@code customer-work.ticket.*}。保留字段只为旧配置平滑升级，运行时不再
 * 注册 HandoffStore/HandoffSlaScheduler。</p>
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
