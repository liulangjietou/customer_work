package com.richard.fyoung.customeradmin.aiconfig.systemtool.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.tool.devtool.DevToolboxTools;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link DevToolboxConfig} 单测：不起 Spring 容器，直接验证 {@code devtoolbox()} 返回可用实例，
 * 并随手调一个工具方法（json_format）确认走通。
 * @author owlzhangfq@gmail.com
 */
class DevToolboxConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void devtoolbox_shouldReturnUsableInstance() throws Exception {
        DevToolboxTools tools = new DevToolboxConfig().devtoolbox();
        assertNotNull(tools, "应返回可用的 DevToolboxTools 实例");

        // 随手调一个工具方法走通：json_format 把格式化文本作为 JSON 字符串字面量回传
        String raw = tools.jsonFormat("{\"a\":1}", 2).block();
        String formatted = mapper.readTree(raw).asText();
        assertEquals(1, mapper.readTree(formatted).get("a").asInt());
    }
}
