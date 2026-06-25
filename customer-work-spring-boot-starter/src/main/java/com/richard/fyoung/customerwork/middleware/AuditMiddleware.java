package com.richard.fyoung.customerwork.middleware;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.observability.AuditSink;
import com.richard.fyoung.customerwork.security.SensitiveDataMasker;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 合规审计中间件（2.0 Middleware，承接 1.x AuditHook，「安全合规 · 可追溯」）。
 *
 * <p>{@link #onActing} 逐条记录工具调用，{@link #onAgent} 记录最终回复与异常，写入只追加审计轨迹
 * （{@link AuditSink}）。工具入参可按配置复用脱敏规则。默认关闭。异常不打断主链路。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class AuditMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(AuditMiddleware.class);

    private final boolean enabled;
    private final boolean maskArgs;
    private final AuditSink sink;
    private final SensitiveDataMasker masker;

    public AuditMiddleware(CustomerWorkProperties properties, AuditSink sink, SensitiveDataMasker masker) {
        this.enabled = properties.getHooks().getAudit().isEnabled();
        this.maskArgs = properties.getHooks().getAudit().isMaskArgs();
        this.sink = sink;
        this.masker = masker;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        if (enabled && input.toolCalls() != null) {
            for (ToolUseBlock use : input.toolCalls()) {
                try {
                    Map<String, Object> f = base(agent);
                    f.put("tool", use == null ? "?" : use.getName());
                    f.put("args", argsText(use));
                    sink.record("tool-call", f);
                } catch (Exception e) {
                    log.error("[AUDIT] tool audit failed, code={}", "AUDIT_ERROR", e);
                }
            }
        }
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        if (!enabled) {
            return next.apply(input);
        }
        return next.apply(input)
            .doOnNext(event -> {
                if (event instanceof AgentResultEvent result) {
                    Map<String, Object> f = base(agent);
                    f.put("answer", maybeMask(textOf(result.getResult())));
                    sink.record("final-answer", f);
                }
            })
            .doOnError(e -> {
                Map<String, Object> f = base(agent);
                f.put("error", e.getMessage());
                sink.record("error", f);
            });
    }

    private Map<String, Object> base(Agent agent) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("agent", agent == null ? "unknown" : agent.getName());
        f.put("ts", System.currentTimeMillis());
        return f;
    }

    String argsText(ToolUseBlock use) {
        if (use == null || use.getInput() == null) {
            return "{}";
        }
        return maybeMask(use.getInput().toString());
    }

    private String maybeMask(String text) {
        return maskArgs ? masker.mask(text) : text;
    }

    private String textOf(Msg msg) {
        if (msg == null) {
            return "";
        }
        String t = msg.getTextContent();
        return t == null ? "" : t;
    }
}
