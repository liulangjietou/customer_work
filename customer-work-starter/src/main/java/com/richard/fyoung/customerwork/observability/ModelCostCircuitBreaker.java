package com.richard.fyoung.customerwork.observability;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

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
 * @author owlzhangfq@gmail.com
 */
@Component
public class ModelCostCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(ModelCostCircuitBreaker.class);

    private final boolean enabled;
    private final int maxTokensPerMinute;
    private final int maxTokensPerHour;

    /** 当前分钟窗口的 token 消耗（分钟时间戳 -> 累计 token）。 */
    private final AtomicLong currentMinuteTokens = new AtomicLong(0);
    private volatile long currentMinuteTimestamp = currentMinute();

    /** 当前小时窗口的 token 消耗（小时时间戳 -> 累计 token）。 */
    private final AtomicLong currentHourTokens = new AtomicLong(0);
    private volatile long currentHourTimestamp = currentHour();

    public ModelCostCircuitBreaker(CustomerWorkProperties properties) {
        this.enabled = properties.getModel().getCostControl().isEnabled();
        this.maxTokensPerMinute = properties.getModel().getCostControl().getMaxTokensPerMinute();
        this.maxTokensPerHour = properties.getModel().getCostControl().getMaxTokensPerHour();
    }

    /** 无参构造（禁用状态，兼容测试）。 */
    public ModelCostCircuitBreaker() {
        this.enabled = false;
        this.maxTokensPerMinute = 0;
        this.maxTokensPerHour = 0;
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

        // 滚动分钟窗口
        long nowMin = currentMinute();
        if (nowMin != currentMinuteTimestamp) {
            currentMinuteTimestamp = nowMin;
            currentMinuteTokens.set(0);
        }

        // 滚动小时窗口
        long nowHour = currentHour();
        if (nowHour != currentHourTimestamp) {
            currentHourTimestamp = nowHour;
            currentHourTokens.set(0);
        }

        // 检查分钟限额
        long minuteAfter = currentMinuteTokens.addAndGet(tokenCount);
        if (minuteAfter > maxTokensPerMinute) {
            log.warn("[CostCircuit] minute limit exceeded: consumed={}, limit={}",
                minuteAfter, maxTokensPerMinute);
            currentMinuteTokens.addAndGet(-tokenCount); // 回滚
            return false;
        }

        // 检查小时限额
        long hourAfter = currentHourTokens.addAndGet(tokenCount);
        if (hourAfter > maxTokensPerHour) {
            log.warn("[CostCircuit] hour limit exceeded: consumed={}, limit={}",
                hourAfter, maxTokensPerHour);
            currentHourTokens.addAndGet(-tokenCount); // 回滚
            currentMinuteTokens.addAndGet(-tokenCount); // 回滚分钟窗口
            return false;
        }

        return true;
    }

    /** 当前是否处于熔断状态（不消耗 token，仅检查）。 */
    public boolean isCircuitOpen() {
        if (!enabled) {
            return false;
        }
        return currentMinuteTokens.get() >= maxTokensPerMinute
            || currentHourTokens.get() >= maxTokensPerHour;
    }

    /** 当前分钟已消耗 token 数（测试用）。 */
    long getMinuteTokens() {
        return currentMinuteTokens.get();
    }

    /** 当前小时已消耗 token 数（测试用）。 */
    long getHourTokens() {
        return currentHourTokens.get();
    }

    private long currentMinute() {
        return System.currentTimeMillis() / 60_000L;
    }

    private long currentHour() {
        return System.currentTimeMillis() / 3_600_000L;
    }
}
