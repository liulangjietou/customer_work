package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话总结建议配置。默认关闭。
 *
 * <p>转人工时对整段历史做一次性 LLM 总结，产出结构化 {@code ConversationSummary} 供接手坐席快速了解上下文。
 * 失败 fail-open（降级到规则版 {@code AgentAssistService} 建议，绝不阻断转人工主链路）。按需触发的
 * {@code GET /api/customer/assist/summary} 端点不受本开关约束（显式人工请求），本开关仅控制转人工时的自动预生成。</p>
 */
@Data
public class AssistProperties {
    /** 转人工时自动预生成会话摘要（默认关，开启会产生真实模型调用费用）。 */
    private boolean summaryEnabled = false;
    /** 摘要拉取会话历史的最大条数。 */
    private int summaryHistoryLimit = 30;
    /** 单次摘要 LLM 调用超时（秒）。 */
    private long summaryTimeoutSeconds = 30;
    /** 摘要缓存的最大会话数（供坐席工作台按 sessionId 拉取预生成结果，超出按插入序淘汰最旧）。 */
    private int summaryCacheMaxSessions = 512;
}
