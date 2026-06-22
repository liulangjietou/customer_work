package com.richard.fyoung.customerwork.agent;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.security.SensitiveDataMasker;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 出站脱敏 Hook（对应「安全合规 · 隐私保护」）。
 *
 * <p>在一次请求结束（{@link PostCallEvent}）时，对最终回复里的手机号 / 身份证 / 银行卡 / 邮箱做掩码，
 * 防止模型把上下文里的敏感信息原样回吐给用户或下游。通过 {@link PostCallEvent#setFinalMessage(Msg)}
 * 改写最终消息，只替换文本内容块，保留消息的角色 / 名称 / 元数据与其它内容块。</p>
 *
 * <p>默认关闭（{@code customer-work.hooks.masking.enabled=false}）。脱敏只作用于对外输出，
 * 不改写工具真实入参，避免破坏业务参数。Hook 异常不打断主链路。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class MaskingHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(MaskingHook.class);

    private final boolean enabled;
    private final boolean maskToolResults;
    private final SensitiveDataMasker masker;

    public MaskingHook(CustomerWorkProperties properties, SensitiveDataMasker masker) {
        this.enabled = properties.getHooks().getMasking().isEnabled();
        this.maskToolResults = properties.getHooks().getMasking().isMaskToolResults();
        this.masker = masker;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!enabled || !masker.hasRules()) {
            return Mono.just(event);
        }
        try {
            if (event instanceof PostCallEvent pce) {
                Msg masked = maskMessage(pce.getFinalMessage());
                if (masked != null) {
                    pce.setFinalMessage(masked);
                }
            } else if (maskToolResults && event instanceof PostActingEvent pae) {
                ToolResultBlock masked = maskToolResult(pae.getToolResult());
                if (masked != null) {
                    pae.setToolResult(masked);
                }
            }
        } catch (Exception e) {
            log.warn("[MASK] 脱敏异常（已忽略，原样下发）: {}", e.getMessage());
        }
        return Mono.just(event);
    }

    /** 对工具结果中的文本块脱敏；无改动则返回 null。 */
    private ToolResultBlock maskToolResult(ToolResultBlock result) {
        if (result == null || result.getOutput() == null || result.getOutput().isEmpty()) {
            return null;
        }
        boolean changed = false;
        List<ContentBlock> rebuilt = new ArrayList<>(result.getOutput().size());
        for (ContentBlock block : result.getOutput()) {
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
        log.info("[MASK] 已对工具结果脱敏 tool={}", result.getName());
        return new ToolResultBlock(result.getId(), result.getName(), rebuilt, result.getMetadata());
    }

    /** 返回脱敏后的新消息；无文本或无需改动则返回 null。 */
    private Msg maskMessage(Msg original) {
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
        log.info("[MASK] 已对最终回复脱敏 agent={}",
            original.getName() == null ? "?" : original.getName());
        return Msg.builder()
            .id(original.getId())
            .name(original.getName())
            .role(original.getRole())
            .content(rebuilt)
            .metadata(original.getMetadata())
            .build();
    }

    @Override
    public int priority() {
        // 较晚运行：在观测 / 审计读取过原文后再做出站脱敏
        return 900;
    }
}
