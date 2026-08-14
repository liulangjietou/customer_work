package com.richard.fyoung.customerwork.capability.deadletter;

import com.richard.fyoung.customerwork.infra.config.properties.DeadLetterProperties;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 死信队列服务：让"失败了就记条 error"变成"失败了会自己补回来"。
 *
 * <p>此前工具调用失败、主动通知发送失败都只落一行日志。业务量小时看不出来，量一上来就是丢单——
 * 用户以为退款申请提交了，下游其实根本没收到，而没有任何机制会发现这件事。</p>
 *
 * <p>重投按类型分发给 {@link DeadLetterHandler}：队列只有载荷，不可能知道怎么重做一次工具调用。
 * 没注册处理器的类型直接跳过并记 error，而不是反复空转——那会让队列看起来在工作、实际什么都没做，
 * 比不重投更危险。</p>
 * @author owlzhangfq@gmail.com
 */
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private final DeadLetterStore store;
    private final DeadLetterProperties properties;
    private final Map<String, DeadLetterHandler> handlers = new HashMap<>();
    private final String instanceId;
    private final MeterRegistry meterRegistry;

    public DeadLetterService(DeadLetterStore store, DeadLetterProperties properties,
                             List<DeadLetterHandler> handlerList) {
        this(store, properties, handlerList, null);
    }

    public DeadLetterService(DeadLetterStore store, DeadLetterProperties properties,
                             List<DeadLetterHandler> handlerList, MeterRegistry meterRegistry) {
        this.store = store;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        for (DeadLetterHandler handler : handlerList) {
            DeadLetterHandler existing = handlers.putIfAbsent(handler.type(), handler);
            if (existing != null) {
                throw new IllegalStateException("duplicate dead letter handler type: " + handler.type());
            }
        }
        this.instanceId = ManagementFactory.getRuntimeMXBean().getName() + "-" + UUID.randomUUID();
        registerDepthGauges();
        log.info("dead letter handlers registered: {}", handlers.keySet());
    }

    /**
     * 登记一条死信（旁路，永不抛出）。
     *
     * <p>调用方通常正处在一个 catch 块里——它自己就是在处理失败，此处再抛异常只会盖掉原始错误。</p>
     *
     * @param type    死信类型，须与某个 {@link DeadLetterHandler#type()} 对应
     * @param payload 重投所需的完整载荷（JSON），必须自包含
     * @param bizKey  关联业务标识（订单号/会话号），供运营检索
     */
    public Optional<DeadLetter> record(String type, String payload, String bizKey, String error) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        try {
            DeadLetter letter = new DeadLetter(UUID.randomUUID().toString(), type, payload, bizKey,
                error, System.currentTimeMillis());
            store.save(letter);
            increment("customerwork.deadletter.recorded", "type", type);
            log.info("dead letter recorded: id={}, type={}, bizKey={}", letter.getId(), type, bizKey);
            if (!handlers.containsKey(type)) {
                log.error("dead letter has no handler, it will never be retried, errorCode={}, type={}",
                    "DEADLETTER-NO-HANDLER", type);
            }
            return Optional.of(letter);
        } catch (Exception e) {
            log.error("record dead letter failed, errorCode={}, type={}, bizKey={}",
                "DEADLETTER-RECORD-FAIL", type, bizKey, e);
            return Optional.empty();
        }
    }

    /**
     * 跑一轮重投：取到期的死信逐条重做。
     *
     * @return 本轮成功重投的条数
     */
    public int retryDue() {
        if (!properties.isEnabled()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        List<DeadLetter> due = CrossTenantOperations.execute(() ->
            store.claimDue(instanceId, now, now + properties.getLeaseMs(), properties.getBatchSize()));
        int succeeded = 0;
        for (DeadLetter letter : due) {
            if (retryOne(letter, now)) {
                succeeded++;
            }
        }
        if (!due.isEmpty()) {
            log.info("dead letter retry round finished: due={}, succeeded={}", due.size(), succeeded);
        }
        return succeeded;
    }

    /** 待重投 / 已放弃列表（运营用）。 */
    public List<DeadLetter> list(DeadLetterStatus status, int limit) {
        return store.findByStatus(status, limit);
    }

    /** 按状态计数。 */
    public long count(DeadLetterStatus status) {
        return store.count(status);
    }

    /**
     * 人工重开一条已放弃的死信（运营确认下游恢复后触发）。
     *
     * @throws IllegalStateException 死信不存在时
     */
    public DeadLetter reopen(String id) {
        DeadLetter letter = store.find(id)
            .orElseThrow(() -> new IllegalStateException("dead letter not found: " + id));
        letter.reopen(System.currentTimeMillis());
        store.save(letter);
        log.info("dead letter reopened: id={}, type={}", id, letter.getType());
        return letter;
    }

    /** 重投一条；成功返回 true。 */
    private boolean retryOne(DeadLetter letter, long nowMs) {
        DeadLetterHandler handler = handlers.get(letter.getType());
        if (handler == null) {
            // 不累计次数：没有处理器不是"重试失败"，累计只会让它悄悄耗尽次数变成已放弃，
            // 掩盖掉"这个类型压根没人处理"这个真正的问题
            log.error("skip dead letter without handler, errorCode={}, id={}, type={}",
                "DEADLETTER-NO-HANDLER", letter.getId(), letter.getType());
            letter.releaseWithoutAttempt(nowMs + properties.getScanIntervalMs());
            ensureLeaseCompletion(letter);
            increment("customerwork.deadletter.retries", "result", "no_handler");
            return false;
        }
        try {
            retryInTenant(handler, letter);
        } catch (Exception e) {
            letter.failAttempt(e.getMessage(), properties.getMaxAttempts(),
                properties.getBaseBackoffMs(), nowMs);
            ensureLeaseCompletion(letter);
            increment("customerwork.deadletter.retries", "result", "failure");
            log.error("dead letter retry failed, errorCode={}, id={}, type={}, attempts={}, status={}",
                "DEADLETTER-RETRY-FAIL", letter.getId(), letter.getType(),
                letter.getAttempts(), letter.getStatus(), e);
            return false;
        }
        letter.succeed(nowMs);
        ensureLeaseCompletion(letter);
        increment("customerwork.deadletter.retries", "result", "success");
        log.info("dead letter retry succeeded: id={}, type={}, attempts={}",
            letter.getId(), letter.getType(), letter.getAttempts());
        return true;
    }

    private void ensureLeaseCompletion(DeadLetter letter) {
        if (!CrossTenantOperations.execute(() -> store.complete(letter, instanceId))) {
            throw new IllegalStateException("dead letter lease lost: " + letter.getId());
        }
    }

    private void retryInTenant(DeadLetterHandler handler, DeadLetter letter) throws Exception {
        String previous = TenantContext.get();
        TenantContext.set(letter.getTenantId());
        try {
            handler.retry(letter);
        } finally {
            TenantContext.set(previous);
        }
    }

    private void registerDepthGauges() {
        if (meterRegistry == null) {
            return;
        }
        for (DeadLetterStatus status : List.of(DeadLetterStatus.PENDING, DeadLetterStatus.PROCESSING,
            DeadLetterStatus.ABANDONED)) {
            Gauge.builder("customerwork.deadletter.depth", store,
                    target -> safeCount(target, status))
                .tag("status", status.name().toLowerCase())
                .register(meterRegistry);
        }
    }

    private double safeCount(DeadLetterStore target, DeadLetterStatus status) {
        try {
            return CrossTenantOperations.execute(() -> target.count(status));
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private void increment(String name, String tagName, String tagValue) {
        if (meterRegistry != null) {
            Counter.builder(name).tag(tagName, tagValue).register(meterRegistry).increment();
        }
    }
}
