package com.richard.fyoung.customerwork.safety.subjectquota;

import com.richard.fyoung.customerwork.infra.counter.WindowCounter;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 主体级速率配额的判定与记账：每个用户 / 每个匿名 IP / 每把 API Key 在滚动窗口内的 token 量与请求次数。
 *
 * <p><b>与租户配额的分工</b>：{@code TenantQuotaGuard} 管"这个客户这个月能花多少钱"（自然日/月对齐，
 * 要跟账单对得上）；本类管"单个调用者这半小时能用多少"（滚动窗口，防的是滥用而非超支）。
 * 两者同时生效，任一触顶都拦——一个客户的总额度没花完，不代表其中某个用户可以一个人把它刷光。</p>
 *
 * <p><b>先判后记，判定只读</b>：{@link #check} 不写任何计数，放行之后由调用方
 * {@link #recordRequest} 计一次、模型调用结束后 {@link #recordTokens} 补真实用量。
 * 因此高并发下允许短暂的少量超额（N 个请求同时读到未超限）——限流不是记账，
 * 为了掐死这几个请求而让每次判定都走一次原子写，代价与收益不成比例。</p>
 *
 * <p><b>被拒的请求不占额度</b>：正因为记账发生在放行之后，持续打压不会把窗口越推越远，
 * 用户等到窗口滚过去就能恢复。</p>
 * @author owlzhangfq@gmail.com
 */
public class SubjectQuotaGuard {

    private static final Logger log = LoggerFactory.getLogger(SubjectQuotaGuard.class);

    /** 次数计数键后缀。与 token 分开计，因为它们是两个独立的上限。 */
    private static final String SUFFIX_REQUEST = ":req";
    private static final String SUFFIX_TOKEN = ":tok";

    private final SubjectLevelResolver levelResolver;
    private final WindowCounter counter;
    private final SubjectQuotaHitStore hitStore;
    private final boolean enabled;

    public SubjectQuotaGuard(SubjectLevelResolver levelResolver,
                             WindowCounter counter,
                             SubjectQuotaHitStore hitStore,
                             boolean enabled) {
        this.levelResolver = levelResolver;
        this.counter = counter;
        this.hitStore = hitStore;
        this.enabled = enabled;
    }

    /**
     * 请求准入判定，超限时异步落一条命中记录。
     *
     * <p>先判次数后判 token：次数是更硬的防刷指标，且两者都超时报次数更容易让用户理解
     * （"你问得太频繁"比"你的额度用完了"更接近他刚才做的事）。</p>
     *
     * @param subject  限流主体；为 null 视为无身份，直接放行（判定不了的场景不该由限流来兜）
     * @param resource 触发位置（HTTP 路径或 {@code ws:chat}），只用于命中记录与排障
     */
    public SubjectQuotaDecision check(QuotaSubject subject, String resource) {
        if (!enabled || subject == null) {
            return SubjectQuotaDecision.allow();
        }
        SubjectQuotaLevel level = levelResolver.resolve(subject);
        if (level == null || !level.effective()) {
            return SubjectQuotaDecision.allow();
        }
        int window = level.effectiveWindowSeconds();

        if (level.hasRequestLimit()) {
            long used = counter.currentSlidingSum(subject.counterKey() + SUFFIX_REQUEST, window);
            if (used >= level.requestLimit()) {
                return reject(SubjectQuotaDecision.exceeded(
                    SubjectQuotaDecision.LimitKind.REQUEST, level, used), subject, resource);
            }
        }
        if (level.hasTokenLimit()) {
            long used = counter.currentSlidingSum(subject.counterKey() + SUFFIX_TOKEN, window);
            if (used >= level.tokenLimit()) {
                return reject(SubjectQuotaDecision.exceeded(
                    SubjectQuotaDecision.LimitKind.TOKEN, level, used), subject, resource);
            }
        }
        return SubjectQuotaDecision.allow();
    }

    /** 记一次请求（判定放行之后调用）。 */
    public void recordRequest(QuotaSubject subject) {
        if (!enabled || subject == null) {
            return;
        }
        SubjectQuotaLevel level = levelResolver.resolve(subject);
        if (level == null || !level.hasRequestLimit()) {
            return;
        }
        counter.incrementSlidingSum(subject.counterKey() + SUFFIX_REQUEST, 1L,
            level.effectiveWindowSeconds());
    }

    /**
     * 记录本次真实 token 消耗（模型调用之后）。
     *
     * <p>取的是实际值而非预估：预估本就不准，用它记账会让额度在"估多了"的方向上白白蒸发。</p>
     */
    public void recordTokens(QuotaSubject subject, long tokens) {
        if (!enabled || subject == null || tokens <= 0) {
            return;
        }
        SubjectQuotaLevel level = levelResolver.resolve(subject);
        if (level == null || !level.hasTokenLimit()) {
            return;
        }
        counter.incrementSlidingSum(subject.counterKey() + SUFFIX_TOKEN, tokens,
            level.effectiveWindowSeconds());
    }

    /** 当前用量快照（给终端用户看"还剩多少"、给后台排障）。 */
    public SubjectQuotaUsage usage(QuotaSubject subject) {
        if (!enabled || subject == null) {
            return SubjectQuotaUsage.unlimited();
        }
        SubjectQuotaLevel level = levelResolver.resolve(subject);
        if (level == null || !level.effective()) {
            return SubjectQuotaUsage.unlimited();
        }
        int window = level.effectiveWindowSeconds();
        return new SubjectQuotaUsage(
            level.levelCode(),
            window,
            counter.currentSlidingSum(subject.counterKey() + SUFFIX_TOKEN, window),
            level.tokenLimit(),
            counter.currentSlidingSum(subject.counterKey() + SUFFIX_REQUEST, window),
            level.requestLimit());
    }

    /** 功能是否开启（调用方据此跳过整段逻辑，省掉无谓的上下文写入）。 */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 落一条命中记录并返回原判定。
     *
     * <p>落库是阻塞 IO，判定却在响应式链路上，故切到弹性线程池异步执行——
     * 让一次统计写入去占事件循环线程，是拿主链路吞吐换观测数据。</p>
     */
    private SubjectQuotaDecision reject(SubjectQuotaDecision decision, QuotaSubject subject, String resource) {
        log.error("subject quota exceeded, code={}, subject={}:{}, level={}, kind={}, used={}, limit={}, action={}",
            "SQUOTA-EXCEEDED", subject.type(), subject.id(), decision.levelCode(),
            decision.kind(), decision.used(), decision.limit(), decision.action());
        if (hitStore == null) {
            return decision;
        }
        String tenantId = TenantContext.get();
        SubjectQuotaHit hit = SubjectQuotaHit.of(
            tenantId == null || tenantId.isBlank() ? TenantContext.DEFAULT : tenantId,
            subject, decision, resource);
        Mono.fromRunnable(() -> hitStore.record(hit))
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe();
        return decision;
    }
}
