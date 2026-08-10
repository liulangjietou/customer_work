package com.richard.fyoung.customerwork.infra.config;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 权限系统配置（AgentScope 2.0 新增能力「Permission System」）。
 *
 * <p>把 {@code customer-work.harness.permission} 映射为框架的 {@link PermissionContextState}：</p>
 * <ul>
 *   <li><b>mode</b>：default（默认按规则）/ acceptEdits / explore（只读探索）/ bypass（全放行）/ dontAsk；</li>
 *   <li><b>ask 规则</b>：命中受控工具（如退款 / 转人工）时进入"询问 / 人工确认"；</li>
 *   <li><b>deny 规则</b>：命中高危工具时直接拒绝。</li>
 * </ul>
 *
 * <p>该 {@link PermissionContextState} 注入主 {@code ReActAgent}（{@code .permissionContext(...)}，
 * 2.0 原生支持），与既有 {@code HumanApprovalHook} 形成"声明式权限 + 编程式确认"的双层闸门。
 * 关闭时不产生 Bean，Agent 行为与迁移前一致。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class PermissionConfig {

    private static final Logger log = LoggerFactory.getLogger(PermissionConfig.class);

    private static final String RULE_SOURCE = "customer-work.harness.permission";

    @Bean
    public PermissionContextState permissionContextState(CustomerWorkProperties properties) {
        CustomerWorkProperties.Harness.Permission cfg = properties.getHarness().getPermission();
        if (!cfg.isEnabled()) {
            // 未启用：返回 trivial（DEFAULT 且无规则），对 Agent 行为无影响，便于无条件注入
            return PermissionContextState.builder().mode(PermissionMode.DEFAULT).build();
        }
        PermissionContextState.Builder builder = PermissionContextState.builder()
            .mode(resolveMode(cfg.getMode()));
        for (String tool : cfg.getAskTools()) {
            builder.addAskRule(tool, new PermissionRule(tool, "*", PermissionBehavior.ASK, RULE_SOURCE));
        }
        for (String tool : cfg.getDenyTools()) {
            builder.addDenyRule(tool, new PermissionRule(tool, "*", PermissionBehavior.DENY, RULE_SOURCE));
        }
        log.info("permission system enabled: mode={} askTools={} denyTools={}",
            cfg.getMode(), cfg.getAskTools(), cfg.getDenyTools());
        return builder.build();
    }

    /** 模式字符串 → 枚举，未知值回退 DEFAULT。 */
    static PermissionMode resolveMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return PermissionMode.DEFAULT;
        }
        switch (mode.trim().toLowerCase()) {
            case "acceptedits":
            case "accept_edits":
                return PermissionMode.ACCEPT_EDITS;
            case "explore":
                return PermissionMode.EXPLORE;
            case "bypass":
                return PermissionMode.BYPASS;
            case "dontask":
            case "dont_ask":
                return PermissionMode.DONT_ASK;
            default:
                return PermissionMode.DEFAULT;
        }
    }
}
