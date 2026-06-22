package com.richard.fyoung.customerwork.agent;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.GenerateOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 动态生成参数 Hook（对应「按意图自适应推理」）。
 *
 * <p>利用 {@link PreReasoningEvent#setGenerateOptions(GenerateOptions)}：当最近一条用户消息命中
 * "投诉 / 退款 / 纠纷" 等高风险关键词时，临时切到"精确档"（更低温度 + 更高推理强度），让模型在
 * 敏感场景下更稳更严谨；普通闲聊则保持默认参数。</p>
 *
 * <p>覆盖采用 {@code GenerateOptions.mergeOptions(override, effective)} 语义（前者非空值优先），
 * 仅覆盖温度与推理强度，其余沿用原有生效参数。默认关闭。Hook 异常不打断主链路。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class DynamicGenerateOptionsHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(DynamicGenerateOptionsHook.class);

    private final boolean enabled;
    private final List<String> preciseKeywords;
    private final Double preciseTemperature;
    private final String preciseReasoningEffort;

    public DynamicGenerateOptionsHook(CustomerWorkProperties properties) {
        CustomerWorkProperties.Hooks.DynamicOptions cfg = properties.getHooks().getDynamicOptions();
        this.enabled = cfg.isEnabled();
        this.preciseKeywords = cfg.getPreciseKeywords();
        this.preciseTemperature = cfg.getPreciseTemperature();
        this.preciseReasoningEffort = cfg.getPreciseReasoningEffort();
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!enabled || !(event instanceof PreReasoningEvent pre)) {
            return Mono.just(event);
        }
        try {
            String lastUser = lastUserText(pre.getInputMessages());
            if (lastUser != null && hitsPreciseKeyword(lastUser)) {
                GenerateOptions override = GenerateOptions.builder()
                    .temperature(preciseTemperature)
                    .reasoningEffort(preciseReasoningEffort)
                    .build();
                // 前者非空值优先：override 覆盖温度/推理强度，其余沿用 effective
                GenerateOptions merged = GenerateOptions.mergeOptions(
                    override, pre.getEffectiveGenerateOptions());
                pre.setGenerateOptions(merged);
                log.info("[OPT] 命中精确档关键词，临时调整 temperature={} reasoningEffort={}",
                    preciseTemperature, preciseReasoningEffort);
            }
        } catch (Exception e) {
            log.warn("[OPT] 动态生成参数异常（已忽略，沿用默认）: {}", e.getMessage());
        }
        return Mono.just(event);
    }

    private String lastUserText(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg != null && msg.getRole() == MsgRole.USER) {
                return msg.getTextContent();
            }
        }
        return null;
    }

    private boolean hitsPreciseKeyword(String text) {
        for (String kw : preciseKeywords) {
            if (kw != null && !kw.isBlank() && text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int priority() {
        // 高优/预处理段：在推理前调整参数
        return 70;
    }
}
