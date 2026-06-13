package com.example.customerwork.memory;

import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 多租户长期记忆（实现框架 {@link LongTermMemory} 接口，对应深度解析 3.4）。
 *
 * <p>每个实例绑定一个租户视图，底层共享 {@link LongTermMemoryStore}：</p>
 * <ul>
 *   <li>{@link #record(List)}：把用户陈述沉淀为该租户的长期事实（只记用户消息，避免噪声）；</li>
 *   <li>{@link #retrieve(Msg)}：按当前问题召回相关历史记忆，拼成提示注入上下文，
 *       让 Agent"越用越懂这个用户"。</li>
 * </ul>
 *
 * <p>挂到 ReActAgent 后，配合 {@code LongTermMemoryMode.BOTH}（静态注入 + Agent 主动调用）
 * 即可在多轮、跨会话场景中复用用户画像与历史诉求。</p>
 */
public class InMemoryLongTermMemory implements LongTermMemory {

    private static final Logger log = LoggerFactory.getLogger(InMemoryLongTermMemory.class);

    private final LongTermMemoryStore store;
    private final FactLog factLog;
    private final String tenantId;
    private final int retrieveTopK;

    public InMemoryLongTermMemory(LongTermMemoryStore store, FactLog factLog,
                                  String tenantId, int retrieveTopK) {
        this.store = store;
        this.factLog = factLog;
        this.tenantId = tenantId;
        this.retrieveTopK = retrieveTopK;
    }

    @Override
    public Mono<Void> record(List<Msg> messages) {
        return Mono.fromRunnable(() -> {
            if (messages == null) {
                return;
            }
            for (Msg msg : messages) {
                if (msg != null && msg.getRole() == MsgRole.USER) {
                    String text = msg.getTextContent();
                    if (text != null && !text.isBlank()) {
                        store.add(tenantId, text);        // L2：可语义召回的长期记忆
                        if (factLog != null) {
                            factLog.append(tenantId, text); // L3：只追加事实日志（可审计）
                        }
                    }
                }
            }
            log.debug("[LTM] 租户 {} 记忆条数={}", tenantId, store.size(tenantId));
        });
    }

    @Override
    public Mono<String> retrieve(Msg query) {
        return Mono.fromSupplier(() -> {
            String q = query == null ? null : query.getTextContent();
            List<String> facts = store.recall(tenantId, q, retrieveTopK);
            if (facts.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("【该用户的历史记忆，供参考】\n");
            for (String fact : facts) {
                sb.append("- ").append(fact).append('\n');
            }
            return sb.toString();
        });
    }
}
