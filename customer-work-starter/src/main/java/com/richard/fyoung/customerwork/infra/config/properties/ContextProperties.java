package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 智能上下文压缩配置（对应 AutoContextMemory）。 */
@Data
public class ContextProperties {
    /** 是否启用自动上下文压缩（长对话上下文有界）。默认关闭，开启需可用模型。 */
    private boolean compressionEnabled = false;
    /** 触发压缩的最大 token 阈值。 */
    private long maxToken = 8000;
    /** 触发压缩的消息条数阈值。 */
    private int msgThreshold = 40;
    /** 压缩时保留最近 N 条消息原文。 */
    private int lastKeep = 10;
}
