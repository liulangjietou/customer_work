package com.richard.fyoung.customerwork.agent;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.observability.AuditSink;
import com.richard.fyoung.customerwork.security.SensitiveDataMasker;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 合规审计 Hook（对应「安全合规 · 可追溯」）。
 *
 * <p>把每次工具调用与最终决策写入只追加审计轨迹（经 {@link AuditSink}），区别于
 * {@code ObservabilityHook} 的聚合指标——审计关注「谁 / 何时 / 调了什么 / 结果如何」的逐条可追溯记录，
 * 用于涉资金等高风险操作的事后审计。工具入参可按配置复用脱敏规则避免泄密。</p>
 *
 * <p>默认关闭（{@code customer-work.hooks.audit.enabled=false}）。Hook 异常不打断主链路。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class AuditHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(AuditHook.class);

    private final boolean enabled;
    private final boolean maskArgs;
    private final AuditSink sink;
    private final SensitiveDataMasker masker;

    public AuditHook(CustomerWorkProperties properties, AuditSink sink, SensitiveDataMasker masker) {
        this.enabled = properties.getHooks().getAudit().isEnabled();
        this.maskArgs = properties.getHooks().getAudit().isMaskArgs();
        this.sink = sink;
        this.masker = masker;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (enabled) {
            try {
                record(event);
            } catch (Exception e) {
                log.warn("[AUDIT] 审计记录异常（已忽略）: {}", e.getMessage());
            }
        }
        return Mono.just(event);
    }

    private void record(HookEvent event) {
        if (event instanceof PostActingEvent e) {
            Map<String, Object> f = base(event);
            ToolUseBlock use = e.getToolUse();
            f.put("tool", use == null ? "?" : use.getName());
            f.put("args", argsText(use));
            f.put("stopRequested", e.isStopRequested());
            ToolResultBlock result = e.getToolResult();
            if (result != null) {
                f.put("suspended", result.isSuspended());
            }
            sink.record("tool-call", f);
        } else if (event instanceof PostCallEvent e) {
            Map<String, Object> f = base(event);
            f.put("answer", maybeMask(textOf(e.getFinalMessage())));
            sink.record("final-answer", f);
        } else if (event instanceof ErrorEvent e) {
            Map<String, Object> f = base(event);
            f.put("error", e.getError() == null ? "?" : e.getError().getMessage());
            sink.record("error", f);
        }
    }

    private Map<String, Object> base(HookEvent event) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("agent", agentName(event));
        f.put("ts", event.getTimestamp());
        return f;
    }

    private String argsText(ToolUseBlock use) {
        if (use == null || use.getInput() == null) {
            return "{}";
        }
        String raw = use.getInput().toString();
        return maybeMask(raw);
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

    private String agentName(HookEvent event) {
        try {
            return event.getAgent() == null ? "unknown" : event.getAgent().getName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override
    public int priority() {
        // 在出站脱敏（900）之前记录，审计可保留原始决策文本（按需另行配置 maskArgs）
        return 850;
    }
}
