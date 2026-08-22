package com.richard.fyoung.customerwork.infra.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 在线连接登记处：维护用户 / 坐席各自的下行帧 Sink，支撑定向推送与广播。
 *
 * <p>每个在线连接对应一个 {@code unicast + onBackpressureBuffer} 的 {@link Sinks.Many}，处理器把其
 * {@code asFlux()} 作为出站流；业务侧（对话分发、工单事件监听）通过 {@code pushToUser/pushToAgent}
 * 把帧写入对应 Sink 实现服务端主动下推。仅依赖 reactor + jackson（与具体 WebFlux 路由无关），故作为
 * 可复用基建下沉 starter，由自动装配扫描注册。</p>
 *
 * <p><b>顶号</b>：同一 id 重复连接时，先 {@code complete} 旧 Sink（旧连接出站流正常结束、随后被清理），
 * 再登记新 Sink——保证一个身份只有一条活跃下行，避免消息分裂到多条连接。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class WsSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(WsSessionRegistry.class);
    private static final String SCOPE_DELIMITER = "\u001f";

    private final Map<String, Sinks.Many<String>> userSinks = new ConcurrentHashMap<>();
    private final Map<String, Sinks.Many<String>> agentSinks = new ConcurrentHashMap<>();
    /** 先封锁登记、再扫描旧连接，配合 register 的写后复查闭合撤权并发窗口。 */
    private final Set<String> restrictedTenants = ConcurrentHashMap.newKeySet();

    private final ObjectMapper objectMapper;

    public WsSessionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 登记用户连接（顶号旧连接），返回该连接下行帧 Sink（处理器用其 asFlux() 作出站流）。 */
    public Sinks.Many<String> registerUser(String userId) {
        return register(userSinks, scoped(userId), "user");
    }

    /** 登记坐席连接（顶号旧连接），返回该连接下行帧 Sink。 */
    public Sinks.Many<String> registerAgent(String agentId) {
        return register(agentSinks, scoped(agentId), "agent");
    }

    /** 注销用户连接（仅当当前登记的正是本 Sink 时移除，避免误删顶号后的新连接）。 */
    public void unregisterUser(String userId, Sinks.Many<String> sink) {
        unregister(userSinks, scoped(userId), sink);
    }

    /** 注销坐席连接。 */
    public void unregisterAgent(String agentId, Sinks.Many<String> sink) {
        unregister(agentSinks, scoped(agentId), sink);
    }

    /** 向用户推送一帧；不在线返回 false（仅 info 日志，不报错——离线是常态）。 */
    public boolean pushToUser(String userId, WsFrame frame) {
        return push(userSinks, scoped(userId), frame, "user");
    }

    /** 向坐席推送一帧；不在线返回 false。 */
    public boolean pushToAgent(String agentId, WsFrame frame) {
        return push(agentSinks, scoped(agentId), frame, "agent");
    }

    /** 向全部在线坐席广播一帧（新工单进队列时唤起抢单）。 */
    public void broadcastToAgents(WsFrame frame) {
        String json = serialize(frame);
        if (json == null) {
            return;
        }
        String prefix = tenantId() + SCOPE_DELIMITER;
        agentSinks.forEach((key, sink) -> {
            if (key.startsWith(prefix)) {
                sink.tryEmitNext(json);
            }
        });
    }

    /** 当前在线用户数（测试/可观测用）。 */
    public int onlineUsers() {
        return userSinks.size();
    }

    /** 当前在线坐席数（测试/可观测用）。 */
    public int onlineAgents() {
        return agentSinks.size();
    }

    /**
     * 租户访问快照冻结/终止后主动断开该租户全部连接。
     *
     * <p>只拒绝新握手仍会让已经建立的 WebSocket 继续收发；访问快照消费者在状态收紧时调用本方法，
     * 以租户前缀原子移除并完成用户、坐席两类 Sink，使在途连接立即正常收尾。</p>
     */
    public void disconnectTenant(String tenantId) {
        if (!TenantContext.isValidTenantId(tenantId)) {
            throw new IllegalArgumentException("tenantId format is invalid");
        }
        String tenantKey = TenantContext.normalizedTenantKey(tenantId);
        boolean newlyRestricted = restrictedTenants.add(tenantKey);
        String prefix = tenantKey + SCOPE_DELIMITER;
        int users = disconnectByPrefix(userSinks, prefix);
        int agents = disconnectByPrefix(agentSinks, prefix);
        if (newlyRestricted || users > 0 || agents > 0) {
            log.info("ws tenant connections disconnected: tenantId={}, users={}, agents={}",
                tenantId, users, agents);
        }
    }

    /** 租户恢复为有效 ACTIVE 快照后才重新允许登记连接。 */
    public void allowTenant(String tenantId) {
        if (!TenantContext.isValidTenantId(tenantId)) {
            throw new IllegalArgumentException("tenantId format is invalid");
        }
        if (restrictedTenants.remove(TenantContext.normalizedTenantKey(tenantId))) {
            log.info("ws tenant connections allowed: tenantId={}", tenantId);
        }
    }

    /**
     * ACTIVE 快照访问版本递增时撤销旧 epoch 的全部长连接，并立即允许新 epoch 重新握手。
     *
     * <p>复用 disconnect 的“先封锁登记、再扫描”并发闭环，扫描完成后再解除封锁；因此旧连接不会漏断，
     * 新凭据也不会被永久拒绝。处于切换窗口内的握手会正常结束，客户端可按新令牌重连。</p>
     */
    public void disconnectTenantSessionsForEpochChange(String tenantId) {
        disconnectTenant(tenantId);
        allowTenant(tenantId);
    }

    public boolean isTenantRestricted(String tenantId) {
        return TenantContext.isValidTenantId(tenantId)
            && restrictedTenants.contains(TenantContext.normalizedTenantKey(tenantId));
    }

    private Sinks.Many<String> register(Map<String, Sinks.Many<String>> sinks, String id, String kind) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        String tenantKey = tenantKey(id);
        if (restrictedTenants.contains(tenantKey)) {
            sink.tryEmitComplete();
            return sink;
        }
        Sinks.Many<String> previous = sinks.put(id, sink);
        if (previous != null) {
            // 先完成旧连接，避免撤权恰好发生在 put 之后时把被替换的旧 Sink 遗漏在扫描外。
            previous.tryEmitComplete();
        }
        // check-then-register 与撤权扫描并发时，写后复查负责移除扫描可能错过的新连接。
        if (restrictedTenants.contains(tenantKey) && sinks.remove(id, sink)) {
            sink.tryEmitComplete();
            return sink;
        }
        if (previous != null) {
            // 顶号：旧连接下行流正常收尾
            log.info("ws {} reconnected, superseding old sink: id={}", kind, id);
        } else {
            log.info("ws {} connected: id={}", kind, id);
        }
        return sink;
    }

    private String tenantKey(String scopedId) {
        int delimiter = scopedId.indexOf(SCOPE_DELIMITER);
        if (delimiter <= 0) {
            throw new IllegalStateException("scoped WebSocket id is invalid");
        }
        return scopedId.substring(0, delimiter);
    }

    private void unregister(Map<String, Sinks.Many<String>> sinks, String id, Sinks.Many<String> sink) {
        // 仅当映射里仍是本 Sink 时移除（顶号后新 Sink 已替换，旧连接注销不应误删新连接）
        if (sinks.remove(id, sink)) {
            sink.tryEmitComplete();
            log.info("ws disconnected: id={}", id);
        }
    }

    private int disconnectByPrefix(Map<String, Sinks.Many<String>> sinks, String prefix) {
        int disconnected = 0;
        for (Map.Entry<String, Sinks.Many<String>> entry : sinks.entrySet()) {
            if (entry.getKey().startsWith(prefix) && sinks.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().tryEmitComplete();
                disconnected++;
            }
        }
        return disconnected;
    }

    private boolean push(Map<String, Sinks.Many<String>> sinks, String id, WsFrame frame, String kind) {
        Sinks.Many<String> sink = sinks.get(id);
        if (sink == null) {
            log.info("ws push skipped, {} offline: id={}, type={}", kind, id, frame.type());
            return false;
        }
        String json = serialize(frame);
        if (json == null) {
            return false;
        }
        return sink.tryEmitNext(json).isSuccess();
    }

    /** 同名用户/坐席在不同租户下必须落入不同连接槽位。 */
    private String scoped(String id) {
        return tenantId() + SCOPE_DELIMITER + id;
    }

    private String tenantId() {
        String tenantId = TenantContext.get();
        return tenantId == null
            ? TenantContext.DEFAULT
            : TenantContext.normalizedTenantKey(tenantId);
    }

    private String serialize(WsFrame frame) {
        try {
            return objectMapper.writeValueAsString(frame);
        } catch (Exception e) {
            log.error("ws frame serialize failed, code={}, type={}", "WS-FRAME-SERIALIZE-FAIL", frame.type(), e);
            return null;
        }
    }
}
