package com.richard.fyoung.customerwork.core.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.harness.agent.HarnessAgent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 会话状态并发写冲突的计量。
 *
 * <h3>为什么升级到 2.0.3 之后需要它</h3>
 * <p>2.0.3 给 {@code AgentStateStore} 加了乐观并发：读状态时带回版本号，写回走
 * {@code saveIfVersion} 做 CAS。MySQL / Redis / 内存三类实现全部 {@code supportsVersioning()=true}，
 * 所以<b>本项目升级后默认就在走这条路径</b>，而框架默认的 {@code ConflictPolicy.OVERWRITE}
 * 会在 CAS 失败时重读最新版本再覆盖——也就是说，<b>冲突发生了，用户和运维都看不见</b>。</p>
 *
 * <p>覆盖本身多数时候是对的（客服会话已由 {@code SessionLock} 串行化，冲突只出现在锁降级
 * 与多副本竞态这类少数场景）。但"多数时候是对的"必须有数据支撑：冲突率长期为 0 说明串行锁在守，
 * 突然抬头则说明锁失效了——这正是 {@code SessionLock} 在 Redis 故障时保护性降级进程内的副作用，
 * 那种降级只记一行日志，除此之外没有任何信号。本指标就是那个信号。</p>
 *
 * <p><b>采集点选在 Agent 释放那一刻</b>：{@link ReActAgent#getStateConflictCount()} 是
 * Agent 实例级的单调累计值，而 Agent 按会话缓存（{@code CustomerServiceService#resolveAgent}），
 * 因此释放时读一次即可拿到"这个会话一共冲突了几次"，不重不漏。全部 11 处释放都走
 * {@link AgentResourceCloser#closeQuietly}，这与本项目"装配只有一个入口"是同一条纪律。</p>
 *
 * <p>没有 {@code MeterRegistry} 时退化为空操作。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class AgentStateConflictMetrics {

    private static final Logger log = LoggerFactory.getLogger(AgentStateConflictMetrics.class);

    /** 冲突累计数；owner 标签区分是哪条链路的 Agent。 */
    private static final String CONFLICTS = "customerwork.agent.state.conflicts";

    /**
     * 释放方标签键。
     *
     * <p>本域独有，故不进 {@code MetricTags}——那里只放跨域共用的键。</p>
     */
    static final String TAG_OWNER = "owner";

    /** owner 缺失时的归一化取值。 */
    static final String OWNER_UNKNOWN = "unknown";

    /**
     * 进程内唯一实例。
     *
     * <p>{@link AgentResourceCloser} 是静态工具类且被 admin（不走 starter 自动装配）复用，
     * 无法注入。同形状的先例是框架自己的 {@code AgentBase.addSystemHook}，
     * 见 {@link GlobalHookRegistry}。</p>
     */
    private static volatile AgentStateConflictMetrics instance;

    /** 可为 null：未接入 Micrometer 时整体降级为空操作。 */
    private final MeterRegistry registry;

    /**
     * Spring 装配入口。
     *
     * <p>{@code @Autowired} 不可省：本类有两个 public 构造器，不标注的话 Spring 会静默挑一个，
     * 而挑错的后果是拿不到 MeterRegistry、指标全程为空——本仓库为这个形状修过 5 处（PR #68）。</p>
     */
    @Autowired
    public AgentStateConflictMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this.registry = registryProvider == null ? null : registryProvider.getIfAvailable();
        instance = this;
    }

    /**
     * 直接构造。
     *
     * <p>public 是给 admin 用的：那个模块 {@code spring.autoconfigure.exclude} 掉了 starter 的
     * 自动装配，需要 starter 能力时在自己的 {@code @Configuration} 里显式 new
     * （见 {@code AdminAgentRuntimeConfig}）。</p>
     */
    public AgentStateConflictMetrics(MeterRegistry registry) {
        this.registry = registry;
        instance = this;
    }

    /** 上下文销毁时摘掉自己，避免泄漏到后续上下文（多个测试上下文串跑时尤其重要）。 */
    @PreDestroy
    public void unregister() {
        if (instance == this) {
            instance = null;
        }
    }

    /**
     * 记一个 Agent 的状态冲突累计数；未装配本 Bean 或取不到内层 {@link ReActAgent} 时静默跳过。
     *
     * <p>可观测是旁路，任何异常都不得影响释放流程本身。</p>
     */
    static void recordQuietly(Agent agent, String owner) {
        AgentStateConflictMetrics current = instance;
        if (current == null) {
            return;
        }
        // try 必须罩住 unwrap 在内的每一步：调用方 AgentResourceCloser#closeQuietly 的契约是
        // 「异常只记录、不阻断释放」，而本方法排在它的 try 之前。这里漏出去一个异常，
        // 关 Agent 与关 Toolkit 就都不会执行——为了一个旁路指标丢掉资源释放，代价完全不成比例。
        try {
            ReActAgent reActAgent = unwrap(agent);
            if (reActAgent != null) {
                current.record(reActAgent.getStateConflictCount(), owner);
            }
        } catch (Exception e) {
            log.error("Agent state conflict metrics failed, code={}, owner={}",
                "AGENT-STATE-CONFLICT-METRIC-FAIL", owner, e);
        }
    }

    /**
     * 取出持有状态的那个 {@link ReActAgent}。
     *
     * <p>{@code HarnessAgent} 必须下钻：它只是包了一层，状态与冲突计数都在内层的
     * {@code delegate} 上（{@code AgentStateAccessor} 出于同样的理由也在下钻）。
     * 不下钻的话 Harness 那条链路的冲突数<b>永远采集不到，而且不会报错</b>——
     * 正是本项目反复踩过的「能力只接在一部分路径上」。</p>
     */
    private static ReActAgent unwrap(Agent agent) {
        if (agent instanceof ReActAgent reActAgent) {
            return reActAgent;
        }
        if (agent instanceof HarnessAgent harnessAgent) {
            return harnessAgent.getDelegate();
        }
        return null;
    }

    /** 记录一次冲突累计数；0 不写点（正常路径应当恒为 0，写了只是给注册表添噪声）。 */
    void record(long conflicts, String owner) {
        if (registry == null || conflicts <= 0) {
            return;
        }
        Counter.builder(CONFLICTS)
            .tag(TAG_OWNER, normalizeOwner(owner))
            .register(registry)
            .increment(conflicts);
        log.info("[AgentState] optimistic write conflicts={}, owner={}", conflicts, owner);
    }

    /**
     * 归一化 owner 为低基数标签。
     *
     * <p>调用方传的是 {@code "agui:conv-8f3a..."} 这类带会话 ID 的串，
     * 直接当标签会让时间序列随会话数无限膨胀（Prometheus 侧的典型事故）。
     * 冒号后的部分是实例标识，对"哪条链路在冲突"这个问题没有信息量，去掉。</p>
     */
    static String normalizeOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            return OWNER_UNKNOWN;
        }
        int colon = owner.indexOf(':');
        String prefix = colon >= 0 ? owner.substring(0, colon) : owner;
        return prefix.isBlank() ? OWNER_UNKNOWN : prefix;
    }
}
