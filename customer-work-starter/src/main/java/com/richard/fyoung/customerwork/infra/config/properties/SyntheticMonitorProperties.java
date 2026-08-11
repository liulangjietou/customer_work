package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 合成监控（主动探活）：定时用固定探针会话打真实对话链路，在用户上报前发现故障。
 *
 * <p><b>默认关闭</b>——每次探测会真实调用模型（产生费用）。开启后建议探测间隔不宜过密。
 * 探针使用独立 {@code sessionId}（与真实会话隔离），每次探测后清理其状态。</p>
 */
@Data
public class SyntheticMonitorProperties {
    /** 是否启用主动探活（默认关闭，开启会产生真实模型调用费用）。 */
    private boolean enabled = false;
    /** 探测间隔（毫秒），默认 5 分钟。 */
    private long intervalMs = 300000;
    /** 单次探测超时（秒）。 */
    private long timeoutSeconds = 30;
    /** 探针会话 ID（独立命名空间，避免污染真实会话）。 */
    private String sessionId = "synthetic:healthcheck";
    /** 探针消息文本。 */
    private String message = "你好";
}
