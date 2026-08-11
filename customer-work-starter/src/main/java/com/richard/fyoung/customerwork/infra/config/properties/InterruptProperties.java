package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 中断恢复配置。 */
@Data
public class InterruptProperties {
    /** 是否启用待执行工具恢复：中断后再次调用可无缝恢复被打断的工具调用。 */
    private boolean pendingToolRecoveryEnabled = true;
}
