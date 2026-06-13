package com.example.customerwork.tool;

import com.example.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Higress 接入装配器单测（接入 Higress）：开关与配置判定逻辑，不连接真实网关。
 */
class HigressToolkitConfigurerTest {

    @Test
    void isEnabled_shouldBeFalse_byDefault() {
        assertFalse(new HigressToolkitConfigurer(new CustomerWorkProperties()).isEnabled());
    }

    @Test
    void isEnabled_shouldBeFalse_whenEnabledButNoEndpoint() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHigress().setEnabled(true);
        assertFalse(new HigressToolkitConfigurer(props).isEnabled(), "未配置 endpoint 视为未启用");
    }

    @Test
    void isEnabled_shouldBeTrue_whenEnabledWithEndpoint() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHigress().setEnabled(true);
        props.getHigress().setEndpoint("http://higress.local/mcp/sse");
        assertTrue(new HigressToolkitConfigurer(props).isEnabled());
    }

    @Test
    void configure_shouldBeNoOp_whenDisabled() {
        Toolkit toolkit = new Toolkit();
        int before = toolkit.getToolNames().size();
        new HigressToolkitConfigurer(new CustomerWorkProperties()).configure(toolkit);
        assertEquals(before, toolkit.getToolNames().size(), "未启用时不应改动 toolkit");
    }
}
