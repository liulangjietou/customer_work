package com.richard.fyoung.customerwork.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;

/**
 * 单个工具调用完成时的观察者：由 {@link ManagedToolkit} 在串行执行模式下逐个回调。
 *
 * <p>为什么不从事件流计时：AgentScope 2.0.5 的 {@code ReActAgent.runToolBatch} 先对批内每个工具
 * 一次性发 {@code ToolResultStartEvent}，整批执行完再一次性发 {@code ToolResultEndEvent}——
 * 两类事件的时间戳都是批次边界，按 {@code toolCallId} 从事件流计时只能得到整批之和。
 * 真正逐个执行的位置是 {@code Toolkit.callTools}，所以计时点放在那里，
 * 再借 {@code RuntimeContext} 把结果交回采集方。</p>
 *
 * <p>实现方必须自己吞掉异常：回调跑在工具执行链路上，抛出去会把一次成功的工具调用变成失败。</p>
 * @author owlzhangfq@gmail.com
 */
public interface ToolCallObserver {

    /**
     * 单个工具执行结束（成功、失败、超时、被框架转成错误结果都算）。
     *
     * @param call          本次工具调用
     * @param result        工具结果；上游以异常结束时为 {@code null}
     * @param startTimeMs   开始时刻（epoch 毫秒）
     * @param durationNanos 实测耗时（纳秒）
     * @param error         上游异常；正常结束时为 {@code null}
     */
    void onToolCompleted(ToolUseBlock call, ToolResultBlock result,
                         long startTimeMs, long durationNanos, Throwable error);
}
