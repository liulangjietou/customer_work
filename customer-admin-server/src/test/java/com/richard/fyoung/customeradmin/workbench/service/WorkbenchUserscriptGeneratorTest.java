package com.richard.fyoung.customeradmin.workbench.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkbenchUserscriptGenerator} 单测：占位符全部被替换、@match/@connect/令牌正确嵌入、
 * 无启用站点时给出兜底 @match。读取真实 classpath 模板。
 * @author owlzhangfq@gmail.com
 */
class WorkbenchUserscriptGeneratorTest {

    private final WorkbenchUserscriptGenerator generator =
        new WorkbenchUserscriptGenerator("http://localhost:8082");

    @Test
    void generate_shouldFillAllPlaceholders() {
        String script = generator.generate("wbt_testtoken", List.of("wiki.example.com", "gitlab.example.com"));

        // 占位符全部替换干净
        assertFalse(script.contains("__TOKEN__"));
        assertFalse(script.contains("__API_BASE__"));
        assertFalse(script.contains("__CONNECT_HOST__"));
        assertFalse(script.contains("__MATCH_BLOCK__"));

        // 令牌 / API 基址 / @connect 主机 / 每站点 @match 均正确嵌入
        assertTrue(script.contains("var TOKEN = 'wbt_testtoken';"));
        assertTrue(script.contains("var API_BASE = 'http://localhost:8082';"));
        assertTrue(script.contains("// @connect      localhost"));
        assertTrue(script.contains("// @match        *://wiki.example.com/*"));
        assertTrue(script.contains("// @match        *://gitlab.example.com/*"));
    }

    @Test
    void generate_shouldFallbackMatch_whenNoHosts() {
        String script = generator.generate("wbt_x", List.of());

        assertFalse(script.contains("__MATCH_BLOCK__"));
        assertTrue(script.contains("暂无启用站点"));
    }
}
