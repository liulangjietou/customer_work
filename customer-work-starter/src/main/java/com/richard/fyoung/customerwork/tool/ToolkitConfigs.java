package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.infra.config.properties.ToolExecutionProperties;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.tool.ToolkitConfig;

import java.time.Duration;

/**
 * Toolkit 运行配置。
 *
 * <p><b>为什么要显式固定串行</b>：AgentScope v2.0.1 把 Toolkit 的默认执行模式从串行改成了并行
 * （release notes 里这条在 "Refactored" 段落而不是 Breaking Changes，实测 {@code defaultConfig()}
 * 的 {@code parallel} 已经是 {@code true}）。工具实现本身是无共享状态的 {@code Mono}，
 * 并行是安全的；不安全的是业务语义与治理语义：</p>
 * <ul>
 *   <li><b>审批交错</b>：模型一轮里同时发起"查订单"和"发起退款"时，{@code HumanApprovalMiddleware}
 *       拦住退款而查询已经跑完。串行下这个交错不存在，而审批被拒时另一个工具的副作用无从回滚；</li>
 *   <li><b>上下文传播</b>：并行意味着工具跑在不同线程上，而"异步回调要显式恢复租户上下文"
 *       这条约定在本项目已经复发过一次。工具并行化会新增一批需要显式传播
 *       {@code TenantContext} / {@code QuotaSubjectContext} 的位置；</li>
 *   <li><b>计量口径</b>：{@code AgentCallTimingMiddleware} 是 token 的唯一落点，
 *       并行调用下的分段耗时统计口径会变。</li>
 * </ul>
 *
 * <p>因此升级到 2.0.2 时把执行模式显式钉回串行，<b>把行为变更与版本升级解耦</b>——
 * 并行化是一件独立的事，要单独评估、单独回归，而不是随一次依赖升版悄悄生效。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class ToolkitConfigs {

    private ToolkitConfigs() {
    }

    /**
     * 串行执行配置：只改 {@code parallel} 这一个维度，其余保持框架默认。
     *
     * <p>实测 {@code ToolkitConfig.defaultConfig()} 与 {@code builder().build()} 完全一致
     * （allowToolDeletion=true、无自定义 executor、executionConfig 与 defaultContext 均为 null），
     * 所以这里只关掉并行，不会顺带改变别的默认行为。</p>
     */
    public static ToolkitConfig sequential() {
        return ToolkitConfig.builder().parallel(false).build();
    }

    /**
     * 串行 + 显式的执行超时与重试。
     *
     * <p><b>为什么必须显式给</b>：实测框架的 {@code ExecutionConfig.TOOL_DEFAULTS} 是
     * {@code timeout=5分钟, maxAttempts=1}。5 分钟对客服对话等于没有超时——订单库慢一次，
     * 用户就对着不动的界面等五分钟。项目在模型侧做了失败转移、熔断、分级路由一整套弹性，
     * 工具侧此前一样都没配。</p>
     *
     * <p><b>重试默认关闭</b>：框架的重试对整个工具集统一生效、不区分幂等性，
     * 而客服工具里有「发起退款」「创建工单」这类重试一次就多做一次的操作——
     * 超时往往意味着请求已经到达下游、只是响应慢了。详见 {@code ToolExecutionProperties#maxAttempts}。</p>
     */
    public static ToolkitConfig sequentialWith(ToolExecutionProperties execution) {
        if (execution == null) {
            return sequential();
        }
        ExecutionConfig.Builder builder = ExecutionConfig.builder()
            .timeout(Duration.ofMillis(execution.getTimeoutMs()))
            .maxAttempts(execution.getMaxAttempts());
        if (execution.getMaxAttempts() > 1) {
            builder.initialBackoff(Duration.ofMillis(execution.getInitialBackoffMs()))
                .maxBackoff(Duration.ofMillis(execution.getMaxBackoffMs()))
                .backoffMultiplier(execution.getBackoffMultiplier());
        }
        return ToolkitConfig.builder()
            .parallel(false)
            .executionConfig(builder.build())
            .build();
    }
}
