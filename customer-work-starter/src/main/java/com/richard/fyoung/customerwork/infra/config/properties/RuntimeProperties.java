package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 运行时与调度配置。 */
@Data
public class RuntimeProperties {
    /** 优雅停机：等待在途请求处理完成的超时（秒）。 */
    private int shutdownTimeoutSeconds = 30;
    /** 是否启用定时维护任务。 */
    private boolean schedulerEnabled = false;
    /** 定时维护任务的执行间隔（毫秒）。 */
    private long schedulerFixedDelayMs = 60_000;
}
