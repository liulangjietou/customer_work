package com.richard.fyoung.customerwork.middleware;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.security.SensitiveDataMasker;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 出站脱敏中间件（2.0 Middleware，承接 1.x MaskingHook，「安全合规 · 隐私保护」）。
 *
 * <p>落在 {@link #onAgent} 段：对下游产出的 {@link AgentResultEvent}（最终回复）里的手机号 / 身份证 /
 * 银行卡 / 邮箱做掩码，防止把上下文敏感信息原样回吐。只替换文本内容块，保留消息角色 / 名称 / 元数据。</p>
 *
 * <p>默认关闭。脱敏只作用于对外输出，不改写工具真实入参。异常不打断主链路。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class MaskingMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(MaskingMiddleware.class);

    private final boolean enabled;
    private final SensitiveDataMasker masker;

    public MaskingMiddleware(CustomerWorkProperties properties, SensitiveDataMasker masker) {
        this.enabled = properties.getHooks().getMasking().isEnabled();
        this.masker = masker;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        if (!enabled || !masker.hasRules()) {
            return next.apply(input);
        }
        return next.apply(input).map(event -> {
            try {
                if (event instanceof AgentResultEvent result) {
                    Msg masked = maskMessage(result.getResult());
                    if (masked != null) {
                        return new AgentResultEvent(masked);
                    }
                }
            } catch (Exception e) {
                log.error("[MASK] masking failed (pass through), code={}", "MASK_ERROR", e);
            }
            return event;
        });
    }

    /** 返回脱敏后的新消息；无文本或无需改动则返回 null。 */
    Msg maskMessage(Msg original) {
        if (original == null) {
            return null;
        }
        List<ContentBlock> blocks = original.getContent();
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }
        boolean changed = false;
        List<ContentBlock> rebuilt = new ArrayList<>(blocks.size());
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock tb) {
                String masked = masker.mask(tb.getText());
                if (masked != null && !masked.equals(tb.getText())) {
                    rebuilt.add(TextBlock.builder().text(masked).build());
                    changed = true;
                    continue;
                }
            }
            rebuilt.add(block);
        }
        if (!changed) {
            return null;
        }
        log.info("[MASK] outbound reply masked, agent={}",
            original.getName() == null ? "?" : original.getName());
        return Msg.builder()
            .id(original.getId())
            .name(original.getName())
            .role(original.getRole())
            .content(rebuilt)
            .metadata(original.getMetadata())
            .build();
    }
}
