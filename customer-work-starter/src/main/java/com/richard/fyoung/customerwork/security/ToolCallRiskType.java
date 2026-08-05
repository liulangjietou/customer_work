package com.richard.fyoung.customerwork.security;

/**
 * {@link ToolCallRiskDetector} 判定出的工具调用风险类型（风险等级由类型隐含，按严重度自高到低排列）。
 *
 * <p>只描述"这次工具调用踩到了哪类风险"，不含处置动作——挂起确认 / 静默改写 / 直接拦截由消费方
 * （中间件、执行模式决策器）自行决定。</p>
 * @author owlzhangfq@gmail.com
 */
public enum ToolCallRiskType {

    /** 删除文件：删除类工具调用。 */
    DELETE,

    /** 执行命令：命中破坏性命令或非只读命令正则（具体哪一种见 {@code ToolCallRisk.reason}）。 */
    RUN_COMMAND,

    /** 修改依赖：写类工具的路径入参命中依赖/构建文件。 */
    MODIFY_DEPENDENCY,

    /** 批量修改：单轮写类工具数超阈值（整步聚合风险，非单次调用风险）。 */
    BATCH_MODIFY
}
