package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 接入装配器单测（特性「MCP 接入」）：开关与配置判定逻辑。
 * 不连接真实 MCP 服务，只验证装配决策。
 * @author owlzhangfq@gmail.com
 */
class McpToolkitConfigurerTest {

    @Test
    void isEnabled_shouldBeFalse_byDefault() {
        McpToolkitConfigurer configurer = new McpToolkitConfigurer(new CustomerWorkProperties());
        assertFalse(configurer.isEnabled(), "默认不启用 MCP");
    }

    @Test
    void isEnabled_shouldBeFalse_whenEnabledButNoServers() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getMcp().setEnabled(true);
        assertFalse(new McpToolkitConfigurer(props).isEnabled(), "未配置服务时视为未启用");
    }

    @Test
    void isEnabled_shouldBeTrue_whenEnabledWithServers() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getMcp().setEnabled(true);
        CustomerWorkProperties.Mcp.Server server = new CustomerWorkProperties.Mcp.Server();
        server.setName("inventory");
        server.setUrl("http://localhost:9000/sse");
        props.getMcp().getServers().add(server);

        assertTrue(new McpToolkitConfigurer(props).isEnabled());
    }

    @Test
    void configure_shouldBeNoOp_whenDisabled() {
        Toolkit toolkit = new Toolkit();
        int before = toolkit.getToolNames().size();

        new McpToolkitConfigurer(new CustomerWorkProperties()).configure(toolkit);

        assertEquals(before, toolkit.getToolNames().size(), "未启用时不应改动 toolkit");
    }
}
