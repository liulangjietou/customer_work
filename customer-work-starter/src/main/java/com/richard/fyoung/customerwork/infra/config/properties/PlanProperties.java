package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 任务规划配置。 */
@Data
public class PlanProperties {
    private boolean enabled = true;
    private int maxSubtasks = 20;
}
