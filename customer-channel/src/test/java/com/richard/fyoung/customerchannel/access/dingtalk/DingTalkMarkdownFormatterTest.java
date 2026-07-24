package com.richard.fyoung.customerchannel.access.dingtalk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DingTalkMarkdownFormatter} 降级规则测试：表格转列表、剥代码围栏、双换行、普通文本透传。
 * @author owlzhangfq@gmail.com
 */
class DingTalkMarkdownFormatterTest {

    @Test
    void shouldConvertTableToKeyValueList() {
        String raw = "打卡信息如下：\n"
            + "| 日期 | 上班打卡 | 下班打卡 |\n"
            + "|------|----------|----------|\n"
            + "| 2026-07-23 | 08:26 | 18:13 |";

        String formatted = DingTalkMarkdownFormatter.format(raw);

        assertTrue(formatted.contains("- 日期: 2026-07-23，上班打卡: 08:26，下班打卡: 18:13"));
        // 表格语法符号不得残留
        assertFalse(formatted.contains("|"));
        assertFalse(formatted.contains("---"));
    }

    @Test
    void shouldStripCodeFencesButKeepContent() {
        String raw = "示例：\n```java\nSystem.out.println(1);\n```\n结束";

        String formatted = DingTalkMarkdownFormatter.format(raw);

        assertFalse(formatted.contains("```"));
        assertTrue(formatted.contains("System.out.println(1);"));
        assertTrue(formatted.contains("结束"));
    }

    @Test
    void shouldDoubleLineBreaksAndCollapseBlankLines() {
        String raw = "第一行\n第二行\n\n\n第三行";

        String formatted = DingTalkMarkdownFormatter.format(raw);

        assertEquals("第一行\n\n第二行\n\n第三行", formatted);
    }

    @Test
    void shouldKeepPlainMarkdownAndHandleBlank() {
        assertEquals("**加粗** 与 [链接](https://a.b)",
            DingTalkMarkdownFormatter.format("**加粗** 与 [链接](https://a.b)"));
        assertEquals("", DingTalkMarkdownFormatter.format(null));
        assertEquals("", DingTalkMarkdownFormatter.format("   "));
    }

    @Test
    void shouldDegradeSingleRowTableToCells() {
        String formatted = DingTalkMarkdownFormatter.format("| 仅一行 | 两列 |");

        assertEquals("- 仅一行，两列", formatted);
    }
}
