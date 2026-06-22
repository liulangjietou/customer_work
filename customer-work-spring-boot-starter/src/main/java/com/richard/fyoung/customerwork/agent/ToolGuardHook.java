package com.richard.fyoung.customerwork.agent;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具调用护栏 Hook（对应「安全 · 工具入参治理」）。
 *
 * <p>利用 {@link PreActingEvent#setToolUse(ToolUseBlock)} 在工具真正执行前改写其入参：</p>
 * <ul>
 *   <li><b>公共参数注入</b>：把渠道 / 租户 / 调用来源等公共上下文注入到每个工具调用（仅当入参缺失该键时），
 *       即官方示例 AuthHook 的典型用法；</li>
 *   <li><b>数值上限钳制</b>：对指定数值参数（如退款金额 amount）做上限保护，超限改写为上限值并告警，
 *       防止模型给出越界参数造成资金/资源事故。</li>
 * </ul>
 *
 * <p>默认关闭。设为高优先级（系统级）以便在其它业务 Hook 之前完成入参治理。Hook 异常不打断主链路。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class ToolGuardHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ToolGuardHook.class);

    private final boolean enabled;
    private final Map<String, String> injectParams;
    private final Map<String, Double> numericCaps;

    public ToolGuardHook(CustomerWorkProperties properties) {
        CustomerWorkProperties.Hooks.ToolGuard cfg = properties.getHooks().getToolGuard();
        this.enabled = cfg.isEnabled();
        this.injectParams = cfg.getInjectParams();
        this.numericCaps = cfg.getNumericCaps();
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!enabled || !(event instanceof PreActingEvent pae)) {
            return Mono.just(event);
        }
        try {
            ToolUseBlock use = pae.getToolUse();
            if (use != null) {
                ToolUseBlock guarded = guard(use);
                if (guarded != null) {
                    pae.setToolUse(guarded);
                }
            }
        } catch (Exception e) {
            log.warn("[GUARD] 工具入参护栏异常（已忽略，原样执行）: {}", e.getMessage());
        }
        return Mono.just(event);
    }

    /** 返回改写后的工具调用；无改动则返回 null。 */
    private ToolUseBlock guard(ToolUseBlock use) {
        Map<String, Object> input = use.getInput() == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(use.getInput());
        boolean changed = false;

        // 1) 公共参数注入（缺失才注入）
        for (Map.Entry<String, String> e : injectParams.entrySet()) {
            if (!input.containsKey(e.getKey())) {
                input.put(e.getKey(), e.getValue());
                changed = true;
            }
        }

        // 2) 数值上限钳制
        for (Map.Entry<String, Double> cap : numericCaps.entrySet()) {
            Object raw = input.get(cap.getKey());
            Double value = toDouble(raw);
            if (value != null && value > cap.getValue()) {
                log.warn("[GUARD] 工具 {} 参数 {}={} 超过上限 {}，已钳制",
                    use.getName(), cap.getKey(), value, cap.getValue());
                input.put(cap.getKey(), cap.getValue());
                changed = true;
            }
        }

        if (!changed) {
            return null;
        }
        return new ToolUseBlock(use.getId(), use.getName(), input, use.getMetadata());
    }

    private Double toDouble(Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw != null) {
            try {
                return Double.parseDouble(raw.toString());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    @Override
    public int priority() {
        // 系统级：在业务 Hook 之前完成入参治理
        return 20;
    }
}
