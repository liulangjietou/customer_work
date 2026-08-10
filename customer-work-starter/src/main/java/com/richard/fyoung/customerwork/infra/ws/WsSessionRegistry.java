package com.richard.fyoung.customerwork.infra.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.Map;
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

    private final Map<String, Sinks.Many<String>> userSinks = new ConcurrentHashMap<>();
    private final Map<String, Sinks.Many<String>> agentSinks = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    public WsSessionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 登记用户连接（顶号旧连接），返回该连接下行帧 Sink（处理器用其 asFlux() 作出站流）。 */
    public Sinks.Many<String> registerUser(String userId) {
        return register(userSinks, userId, "user");
    }

    /** 登记坐席连接（顶号旧连接），返回该连接下行帧 Sink。 */
    public Sinks.Many<String> registerAgent(String agentId) {
        return register(agentSinks, agentId, "agent");
    }

    /** 注销用户连接（仅当当前登记的正是本 Sink 时移除，避免误删顶号后的新连接）。 */
    public void unregisterUser(String userId, Sinks.Many<String> sink) {
        unregister(userSinks, userId, sink);
    }

    /** 注销坐席连接。 */
    public void unregisterAgent(String agentId, Sinks.Many<String> sink) {
        unregister(agentSinks, agentId, sink);
    }

    /** 向用户推送一帧；不在线返回 false（仅 info 日志，不报错——离线是常态）。 */
    public boolean pushToUser(String userId, WsFrame frame) {
        return push(userSinks, userId, frame, "user");
    }

    /** 向坐席推送一帧；不在线返回 false。 */
    public boolean pushToAgent(String agentId, WsFrame frame) {
        return push(agentSinks, agentId, frame, "agent");
    }

    /** 向全部在线坐席广播一帧（新工单进队列时唤起抢单）。 */
    public void broadcastToAgents(WsFrame frame) {
        String json = serialize(frame);
        if (json == null) {
            return;
        }
        for (Sinks.Many<String> sink : agentSinks.values()) {
            sink.tryEmitNext(json);
        }
    }

    /** 当前在线用户数（测试/可观测用）。 */
    public int onlineUsers() {
        return userSinks.size();
    }

    /** 当前在线坐席数（测试/可观测用）。 */
    public int onlineAgents() {
        return agentSinks.size();
    }

    private Sinks.Many<String> register(Map<String, Sinks.Many<String>> sinks, String id, String kind) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Many<String> previous = sinks.put(id, sink);
        if (previous != null) {
            // 顶号：旧连接下行流正常收尾
            previous.tryEmitComplete();
            log.info("ws {} reconnected, superseding old sink: id={}", kind, id);
        } else {
            log.info("ws {} connected: id={}", kind, id);
        }
        return sink;
    }

    private void unregister(Map<String, Sinks.Many<String>> sinks, String id, Sinks.Many<String> sink) {
        // 仅当映射里仍是本 Sink 时移除（顶号后新 Sink 已替换，旧连接注销不应误删新连接）
        if (sinks.remove(id, sink)) {
            sink.tryEmitComplete();
            log.info("ws disconnected: id={}", id);
        }
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

    private String serialize(WsFrame frame) {
        try {
            return objectMapper.writeValueAsString(frame);
        } catch (Exception e) {
            log.error("ws frame serialize failed, code={}, type={}", "WS-FRAME-SERIALIZE-FAIL", frame.type(), e);
            return null;
        }
    }
}
