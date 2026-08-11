package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Agent 运行时配置。 */
@Data
public class AgentProperties {
    private int maxIters = 10;
    /** Meta-Tool（元工具）：Agent 运行时自主启停工具组，缓解上下文窗口压力。 */
    private boolean metaToolEnabled = false;
}
