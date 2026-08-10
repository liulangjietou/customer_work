package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 工具调用护栏中间件（2.0 Middleware，承接 1.x ToolGuardHook，对应「安全 · 工具入参治理」）。
 *
 * <p>落在 {@link #onActing} 段：在工具真正执行前改写 {@link ActingInput} 中的工具入参——</p>
 * <ul>
 *   <li><b>公共参数注入</b>：把渠道 / 租户 / 调用来源等公共上下文注入到每个工具调用（仅当缺失该键时）；</li>
 *   <li><b>数值上限钳制</b>：对指定数值参数（如退款金额 amount）做上限保护，超限改写为上限值并告警；</li>
 *   <li><b>破坏性命令拦截</b>：对字符串入参匹配危险模式（rm -rf、删除沙箱 workspace、format 等），
 *       命中则改写为安全占位并告警，缓解框架 #1898/#1896（沙箱可删 workspace / 跨用户写）。</li>
 * </ul>
 *
 * <p>onActing 骨架与破坏性入参改写委托给 {@link ToolCallRewriteCore}（各模块护栏共用同一实现），
 * 本类只保留客服业务特有的参数注入与数值钳制，以及自己的配置源 {@link CustomerWorkProperties}。</p>
 *
 * <p>默认关闭。异常不打断主链路（原样放行）。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class ToolGuardMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ToolGuardMiddleware.class);

    /** 日志前缀（告警关键字，勿改）。 */
    private static final String LOG_SCOPE = "[GUARD]";
    /** 破坏性命令拦截错误码。 */
    private static final String CODE_DESTRUCTIVE = "TOOL-GUARD-DESTRUCTIVE";
    /** 护栏自身异常（原样放行）错误码。 */
    private static final String CODE_GUARD_FAIL = "TOOL_GUARD_ERROR";

    private final boolean enabled;
    private final Map<String, String> injectParams;
    private final Map<String, Double> numericCaps;
    /** 破坏性入参改写与 onActing 骨架（规则源是本模块自己的配置）。 */
    private final ToolCallRewriteCore rewriteCore;

    public ToolGuardMiddleware(CustomerWorkProperties properties) {
        CustomerWorkProperties.Hooks.ToolGuard cfg = properties.getHooks().getToolGuard();
        this.enabled = cfg.isEnabled();
        this.injectParams = cfg.getInjectParams();
        this.numericCaps = cfg.getNumericCaps();
        // 破坏性入参正则（预编译，不区分大小写）
        List<Pattern> destructivePatterns = new ArrayList<>();
        if (cfg.getDestructivePatterns() != null) {
            for (String p : cfg.getDestructivePatterns()) {
                if (p != null && !p.isEmpty()) {
                    destructivePatterns.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE));
                }
            }
        }
        this.rewriteCore = new ToolCallRewriteCore(text -> matchesAny(destructivePatterns, text),
            CustomerWorkProperties.Hooks.ToolGuard.DESTRUCTIVE_PLACEHOLDER,
            LOG_SCOPE, CODE_DESTRUCTIVE, CODE_GUARD_FAIL);
    }

    /** 命中破坏性入参的累计次数（供单测断言 / 监控采样）。 */
    long destructiveHitCount() {
        return rewriteCore.destructiveHitCount();
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        if (!enabled) {
            return next.apply(input);
        }
        return rewriteCore.guardActing(input, next, this::guard);
    }

    /** 返回改写后的工具调用；无改动则返回 null。 */
    ToolUseBlock guard(ToolUseBlock use) {
        if (use == null) {
            return null;
        }
        Map<String, Object> in = use.getInput() == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(use.getInput());
        boolean changed = false;

        for (Map.Entry<String, String> e : injectParams.entrySet()) {
            if (!in.containsKey(e.getKey())) {
                in.put(e.getKey(), e.getValue());
                changed = true;
            }
        }
        for (Map.Entry<String, Double> cap : numericCaps.entrySet()) {
            Double value = toDouble(in.get(cap.getKey()));
            if (value != null && value > cap.getValue()) {
                log.info("[GUARD] tool {} param {}={} exceeds cap {}, clamped",
                    use.getName(), cap.getKey(), value, cap.getValue());
                in.put(cap.getKey(), cap.getValue());
                changed = true;
            }
        }
        // 破坏性命令拦截：命中即改写为安全占位并计数告警（注入后的参数一并纳入扫描范围）
        changed |= rewriteCore.rewriteDestructiveParams(use.getName(), in);
        if (!changed) {
            return null;
        }
        return new ToolUseBlock(use.getId(), use.getName(), in, use.getMetadata());
    }

    /** 字符串是否命中任一破坏性模式。 */
    private static boolean matchesAny(List<Pattern> patterns, String text) {
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
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
}
