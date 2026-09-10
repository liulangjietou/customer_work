package com.richard.fyoung.customerwork.infra.config.properties;

import io.agentscope.core.state.ConflictPolicy;
import lombok.Data;

/** Agent 运行时配置。 */
@Data
public class AgentProperties {
    private int maxIters = 10;
    /** Meta-Tool（元工具）：Agent 运行时自主启停工具组，缓解上下文窗口压力。 */
    private boolean metaToolEnabled = false;

    /**
     * 会话状态并发写冲突的处理策略（AgentScope 2.0.3 起）。
     *
     * <p>2.0.3 给 {@code AgentStateStore} 加了乐观并发：读状态时带回版本号，写回时用
     * {@code saveIfVersion} 做 CAS。MySQL / Redis / 内存三类实现都已 {@code supportsVersioning()=true}，
     * 因此<b>本项目升级到 2.0.3 后默认就走版本化写入</b>，本配置决定 CAS 失败那一刻怎么办：</p>
     * <ul>
     *   <li>{@code OVERWRITE}：重读最新版本后覆盖写（框架默认，保持既有"最后写入者赢"的行为）；</li>
     *   <li>{@code FAIL}：抛 {@code ConcurrentSessionModificationException} 让调用方感知；</li>
     *   <li>{@code APPEND_MERGE}：把本次新增的消息追加到最新版本之后。</li>
     * </ul>
     *
     * <p>本项目默认保持 {@code OVERWRITE}：客服会话已由 {@code SessionLock} 做串行化，
     * 真正并发写只出现在锁降级（Redis 故障回落进程内）与多副本竞态这类少数场景，
     * 此时中断用户对话（FAIL）比覆盖一次状态更伤。冲突发生的频率由
     * {@code customerwork.agent.state.conflicts} 指标回答（Prometheus 侧名为 customerwork_agent_state_conflicts_total），不是靠猜。</p>
     */
    private ConflictPolicy stateConflictPolicy = ConflictPolicy.OVERWRITE;

    /**
     * 是否启用框架的最终答复过滤（AgentScope 2.0.3 新增 {@code FinalAnswerFilterMiddleware}）。
     *
     * <p>它按「一轮模型调用」缓冲文本事件：该轮一旦出现工具调用，就把已缓冲的文本整段丢弃，
     * 只放行不带工具调用那一轮的文本。用意是不让"我先查一下订单"这类中间话术混进最终答复。</p>
     *
     * <p><b>默认关闭，因为代价对本项目的 C 端不可接受</b>：它要等到 {@code ModelCallEndEvent}
     * 才把缓冲的文本一次性放出，<b>流式打字机效果因此完全消失</b>——用户会盯着空白等上几秒，
     * 然后整段答复突然出现。而客服场景里"好的，我帮您查一下"恰恰是有价值的等待反馈。
     * 只有在非流式集成（如渠道机器人只取最终文本）里才值得打开。</p>
     */
    private boolean finalAnswerFilterEnabled = false;
}
