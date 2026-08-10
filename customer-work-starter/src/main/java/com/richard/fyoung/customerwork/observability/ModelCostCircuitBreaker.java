package com.richard.fyoung.customerwork.observability;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.counter.InMemoryWindowCounter;
import com.richard.fyoung.customerwork.counter.WindowCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 模型成本熔断器（P2：防止 token 消耗超预算打爆成本）。
 *
 * <p>按时间窗口追踪 token 消耗量，超过阈值时熔断（拒绝后续请求）。</p>
 *
 * <p>支持两个维度：</p>
 * <ul>
 *   <li><b>每分钟限额</b>：防止突发流量打爆成本（如被刷量攻击）；</li>
 *   <li><b>每小时限额</b>：控制小时级总成本上限。</li>
 * </ul>
 *
 * <p>使用方法：在模型调用前调用 {@link #tryConsume(int)} 检查是否可用，可用则记录消耗量；
 * 不可用则返回 false，由调用方决定降级策略（如返回兜底回复、降级到更便宜的模型）。</p>
 *
 * <p>仅在 {@code model.cost-control.enabled=true} 时生效；默认关闭。</p>
 *
 * <p>计数走 {@link WindowCounter}：默认进程内，多副本部署把
 * {@code customer-work.distributed.counter-mode} 切成 {@code redis} 才是真正的成本上限——
 * 否则每个实例各有一份配额，实际花销是配置值的实例数倍，"熔断"形同虚设。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class ModelCostCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(ModelCostCircuitBreaker.class);

    private static final String MINUTE_KEY = "modelcost:minute";
    private static final String HOUR_KEY = "modelcost:hour";
    private static final int MINUTE_WINDOW_SECONDS = 60;
    private static final int HOUR_WINDOW_SECONDS = 3600;

    private final boolean enabled;
    private final int maxTokensPerMinute;
    private final int maxTokensPerHour;
    private final WindowCounter counter;

    /**
     * Spring 注入构造：读取 {@code model.cost-control.*} 配置。
     *
     * <p>必须标 {@code @Autowired}：本类同时存在无参构造，Spring 对「多构造器 + 存在无参 + 无
     * {@code @Autowired}」会回退到无参构造 → 熔断器永远处于禁用态、
     * {@code model.cost-control.enabled=true} 空转。</p>
     */
    @Autowired
    public ModelCostCircuitBreaker(CustomerWorkProperties properties,
                                   ObjectProvider<WindowCounter> counterProvider) {
        this.enabled = properties.getModel().getCostControl().isEnabled();
        this.maxTokensPerMinute = properties.getModel().getCostControl().getMaxTokensPerMinute();
        this.maxTokensPerHour = properties.getModel().getCostControl().getMaxTokensPerHour();
        WindowCounter provided = counterProvider == null ? null : counterProvider.getIfAvailable();
        this.counter = provided == null ? new InMemoryWindowCounter() : provided;
    }

    /** 便捷构造：进程内计数。单实例部署与测试用这个即可，多副本请走带 {@link WindowCounter} 的构造。 */
    public ModelCostCircuitBreaker(CustomerWorkProperties properties) {
        this(properties, (ObjectProvider<WindowCounter>) null);
    }

    /** 无参构造（禁用状态，兼容测试）。 */
    public ModelCostCircuitBreaker() {
        this.enabled = false;
        this.maxTokensPerMinute = 0;
        this.maxTokensPerHour = 0;
        this.counter = new InMemoryWindowCounter();
    }

    /**
     * 尝试消耗 token：检查是否在限额内，如果是则记录消耗量。
     *
     * @param tokenCount 本次请求预计消耗的 token 数
     * @return true=允许消耗（已记录），false=熔断中（不允许）
     */
    public boolean tryConsume(int tokenCount) {
        if (!enabled || tokenCount <= 0) {
            return true;
        }

        // 窗口滚动由计数器实现负责：键自带窗口序号，跨窗口自然归零，不需要在这里比对时间戳
        long minuteAfter = counter.increment(MINUTE_KEY, tokenCount, MINUTE_WINDOW_SECONDS);
        if (minuteAfter > maxTokensPerMinute) {
            log.error("model cost minute limit exceeded, code={}, consumed={}, limit={}",
                "MODEL-COST-MINUTE-LIMIT", minuteAfter, maxTokensPerMinute);
            counter.decrement(MINUTE_KEY, tokenCount, MINUTE_WINDOW_SECONDS);
            return false;
        }

        long hourAfter = counter.increment(HOUR_KEY, tokenCount, HOUR_WINDOW_SECONDS);
        if (hourAfter > maxTokensPerHour) {
            log.error("model cost hour limit exceeded, code={}, consumed={}, limit={}",
                "MODEL-COST-HOUR-LIMIT", hourAfter, maxTokensPerHour);
            counter.decrement(HOUR_KEY, tokenCount, HOUR_WINDOW_SECONDS);
            // 分钟窗口的增量也要退回：这次请求整体被拒，不该占用任何一个窗口的额度
            counter.decrement(MINUTE_KEY, tokenCount, MINUTE_WINDOW_SECONDS);
            return false;
        }

        return true;
    }

    /** 当前是否处于熔断状态（不消耗 token，仅检查）。 */
    public boolean isCircuitOpen() {
        if (!enabled) {
            return false;
        }
        return getMinuteTokens() >= maxTokensPerMinute || getHourTokens() >= maxTokensPerHour;
    }

    /** 当前分钟已消耗 token 数（测试用）。 */
    long getMinuteTokens() {
        return counter.current(MINUTE_KEY, MINUTE_WINDOW_SECONDS);
    }

    /** 当前小时已消耗 token 数（测试用）。 */
    long getHourTokens() {
        return counter.current(HOUR_KEY, HOUR_WINDOW_SECONDS);
    }
}
