package com.richard.fyoung.customerwork.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 人工坐席通道工具（对应流程图④"人工坐席通道"与③"情绪/风险熔断"）。
 *
 * <p>当出现以下情况时，主 Agent 应调用本工具升级到人工：</p>
 * <ul>
 *   <li>用户情绪强烈、明确要求人工；</li>
 *   <li>涉及大额资金、投诉升级等高风险场景；</li>
 *   <li>多轮仍无法解决。</li>
 * </ul>
 *
 * <p>生产中本工具会把当前 {@code sessionId} 对应的完整上下文（依托框架的 Session/State）
 * 推送到人工坐席工作台。借助 ReActAgent 的安全中断能力，移交时上下文与工具状态被完整保存，
 * 人工处理后可无缝恢复。</p>
 * @author owlzhangfq@gmail.com
 */
public class HumanHandoffTools {

    private static final Logger log = LoggerFactory.getLogger(HumanHandoffTools.class);

    @Tool(description = "将当前会话升级转接到人工坐席。当用户情绪激动、明确要求人工、或涉及高风险/大额资金/投诉升级时调用。")
    public Mono<String> transferToHuman(
            @ToolParam(name = "reason", description = "转人工的原因，例如 '用户投诉升级' '涉及大额退款'")
            String reason) {
        log.warn("[HumanHandoffTools] 触发人工转接，原因: {}", reason);
        return Mono.fromSupplier(() -> {
                String workOrderId = "HO" + System.currentTimeMillis();
                // 生产：此处把上下文推送到坐席工作台 / 工单系统
                return String.format(
                    "已为您转接人工坐席（工单号 %s，原因：%s）。"
                  + "您的对话记录已完整同步给坐席，无需重复描述，请稍候。",
                    workOrderId, reason);
            })
            .onErrorResume(e -> {
                log.error("[HumanHandoffTools] 人工转接失败", e);
                return Mono.just("人工坐席通道繁忙，已为您记录，将尽快回拨。");
            });
    }
}
