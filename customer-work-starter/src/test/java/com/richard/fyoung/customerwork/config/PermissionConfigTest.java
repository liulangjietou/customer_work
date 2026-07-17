package com.richard.fyoung.customerwork.config;

import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限系统配置单测（2.0 新增能力「Permission System」）：模式映射、ask/deny 规则装配、关闭时 trivial。
 * @author owlzhangfq@gmail.com
 */
class PermissionConfigTest {

    private final PermissionConfig config = new PermissionConfig();

    @Test
    void resolveMode_shouldMapStringsToEnum() {
        assertEquals(PermissionMode.DEFAULT, PermissionConfig.resolveMode("default"));
        assertEquals(PermissionMode.ACCEPT_EDITS, PermissionConfig.resolveMode("acceptEdits"));
        assertEquals(PermissionMode.EXPLORE, PermissionConfig.resolveMode("explore"));
        assertEquals(PermissionMode.BYPASS, PermissionConfig.resolveMode("bypass"));
        assertEquals(PermissionMode.DONT_ASK, PermissionConfig.resolveMode("dontAsk"));
        assertEquals(PermissionMode.DEFAULT, PermissionConfig.resolveMode("unknown"));
    }

    @Test
    void permissionContextState_shouldBeTrivial_whenDisabled() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHarness().getPermission().setEnabled(false);

        PermissionContextState state = config.permissionContextState(props);

        assertEquals(PermissionMode.DEFAULT, state.getMode());
        assertTrue(state.getAskRules().isEmpty(), "未启用时不应有 ask 规则");
    }

    @Test
    void permissionContextState_shouldApplyRules_whenEnabled() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        CustomerWorkProperties.Harness.Permission p = props.getHarness().getPermission();
        p.setEnabled(true);
        p.setMode("explore");

        PermissionContextState state = config.permissionContextState(props);

        assertEquals(PermissionMode.EXPLORE, state.getMode());
        // 默认 askTools 含退款 / 转人工
        assertTrue(state.getAskRules().containsKey("submitRefund"), "应为受控工具生成 ask 规则");
        assertTrue(state.getAskRules().containsKey("transferToHuman"));
    }
}
