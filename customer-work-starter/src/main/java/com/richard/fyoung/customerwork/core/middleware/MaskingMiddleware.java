package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.security.SensitiveDataMasker;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 出站脱敏中间件（2.0 Middleware，承接 1.x MaskingHook，「安全合规 · 隐私保护」）。
 *
 * <p>落在 {@link #onAgent} 段：对下游产出的流式文本块和 {@link AgentResultEvent}（最终回复）里的手机号 /
 * 身份证 / 银行卡 / 邮箱做掩码，防止把上下文敏感信息原样回吐。只替换文本内容块，保留消息角色 / 名称 /
 * 元数据。</p>
 *
 * <p><b>流式安全语义</b>：正则（尤其邮箱和自定义规则）可能是无界长度，有限滑动窗口无法证明某个前缀
 * 永远不会在后续 chunk 中组成敏感数据。因此启用 PII 脱敏后，本中间件按 text block 缓冲，收到
 * {@link TextBlockEndEvent} 或流结束时先整段脱敏，再发出唯一安全 delta。这里明确选择隐私保证优先于逐 token
 * 延迟，不能用“保留若干字符”的经验值制造跨 chunk 泄漏窗口。</p>
 *
 * <p>默认关闭。脱敏只作用于对外输出，不改写工具真实入参。流式脱敏异常 fail-closed，绝不回退放行原始
 * delta；最终结果的兼容路径仍保持原有不打断主链路语义。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class MaskingMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(MaskingMiddleware.class);
    /** 防止异常模型生成无限正文占满堆；超限块 fail-closed，不释放已缓存原文。 */
    private static final int MAX_BLOCK_CHARS = 1_048_576;

    private final boolean enabled;
    private final SensitiveDataMasker masker;

    @Autowired
    public MaskingMiddleware(CustomerWorkProperties properties, SensitiveDataMasker masker) {
        this(properties.getHooks().getMasking().isEnabled(), masker);
    }

    /**
     * 参数化构造：供已排除 starter 自动装配的模块（如 customer-admin-server）显式装配。
     *
     * <p>没有这个构造时，admin 侧无法挂载本中间件——后台链路因此长期缺失出站脱敏，
     * 而运维在客服端验证过脱敏生效后会理所当然以为全局都保护上了。
     * 与 {@code IndirectInjectionGuardMiddleware} 的参数化构造同一用途。</p>
     */
    public MaskingMiddleware(boolean enabled, SensitiveDataMasker masker) {
        this.enabled = enabled;
        this.masker = masker;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        if (!enabled || !masker.hasRules()) {
            return next.apply(input);
        }
        return Flux.defer(() -> {
            OutboundStreamState state = new OutboundStreamState();
            return next.apply(input)
                .concatMap(event -> maskOutboundEvent(event, state))
                .concatWith(Flux.defer(() -> flushRemaining(state)));
        });
    }

    private Flux<AgentEvent> maskOutboundEvent(AgentEvent event, OutboundStreamState state) {
        try {
            if (event instanceof TextBlockDeltaEvent delta) {
                TextBlockBuffer buffer = state.blocks.computeIfAbsent(delta.getBlockId(),
                    key -> new TextBlockBuffer(delta.getReplyId(), delta.getBlockId()));
                if (!buffer.overflowed) {
                    if (buffer.text.length() + delta.getDelta().length() > MAX_BLOCK_CHARS) {
                        buffer.overflowed = true;
                        buffer.text.setLength(0);
                        log.error("[MASK] outbound text block exceeds limit and was dropped, code={}, blockId={}",
                            "MASK_STREAM_LIMIT", delta.getBlockId());
                    } else {
                        buffer.text.append(delta.getDelta());
                    }
                }
                return Flux.empty();
            }
            if (event instanceof TextBlockEndEvent end) {
                TextBlockBuffer buffer = state.blocks.remove(end.getBlockId());
                return buffer == null ? Flux.just(end) : flushBlock(buffer, end);
            }
            if (event instanceof AgentResultEvent result) {
                Msg masked = maskMessage(result.getResult());
                return Flux.just(masked == null ? event : new AgentResultEvent(masked));
            }
            return Flux.just(event);
        } catch (Exception e) {
            // 流式正文的原文不能因脱敏器异常而旁路出站。
            log.error("[MASK] outbound stream masking failed and event was dropped, code={}",
                "MASK_STREAM_ERROR", e);
            return event instanceof TextBlockDeltaEvent ? Flux.empty() : Flux.just(event);
        }
    }

    private Flux<AgentEvent> flushBlock(TextBlockBuffer buffer, TextBlockEndEvent end) {
        if (buffer.overflowed || buffer.text.length() == 0) {
            return Flux.just(end);
        }
        String original = buffer.text.toString();
        String masked = masker.mask(original);
        if (!original.equals(masked)) {
            log.info("[MASK] outbound stream block masked, blockId={}", buffer.blockId);
        }
        return Flux.just(new TextBlockDeltaEvent(buffer.replyId, buffer.blockId, masked), end);
    }

    /** 上游异常地未发送 block-end 时，流完成仍必须 flush，不能吞尾巴或绕过脱敏。 */
    private Flux<AgentEvent> flushRemaining(OutboundStreamState state) {
        List<AgentEvent> remaining = new ArrayList<>();
        for (TextBlockBuffer buffer : state.blocks.values()) {
            if (buffer.overflowed || buffer.text.length() == 0) {
                continue;
            }
            String original = buffer.text.toString();
            remaining.add(new TextBlockDeltaEvent(buffer.replyId, buffer.blockId, masker.mask(original)));
        }
        state.blocks.clear();
        return Flux.fromIterable(remaining);
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

    private static final class OutboundStreamState {
        private final Map<String, TextBlockBuffer> blocks = new LinkedHashMap<>();
    }

    private static final class TextBlockBuffer {
        private final String replyId;
        private final String blockId;
        private final StringBuilder text = new StringBuilder();
        private boolean overflowed;

        private TextBlockBuffer(String replyId, String blockId) {
            this.replyId = replyId;
            this.blockId = blockId;
        }
    }

    /** 顺序契约见 {@link MiddlewareOrders}：出站方向最先执行，把个人信息挡在后续留痕之前。 */
    @Override
    public int order() {
        return MiddlewareOrders.MASKING;
    }
}
