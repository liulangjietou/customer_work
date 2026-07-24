package com.richard.fyoung.customerchannel.access.wechat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WeChatTextFormatter} 纯文本降级测试：表格转列表、剥加粗/行内码/标题符、链接改写、代码围栏、换行保留。
 * @author owlzhangfq@gmail.com
 */
class WeChatTextFormatterTest {

    @Test
    void shouldConvertTableToKeyValueList() {
        String raw = "打卡信息如下：\n"
            + "| 日期 | 上班打卡 | 下班打卡 |\n"
            + "|------|----------|----------|\n"
            + "| 2026-07-23 | 08:26 | 18:13 |";

        String formatted = WeChatTextFormatter.format(raw);

        assertTrue(formatted.contains("- 日期: 2026-07-23，上班打卡: 08:26，下班打卡: 18:13"));
        assertFalse(formatted.contains("|"));
        assertFalse(formatted.contains("---"));
    }

    @Test
    void shouldStripBoldInlineCodeAndHeading() {
        assertEquals("加粗", WeChatTextFormatter.format("**加粗**"));
        assertEquals("行内码", WeChatTextFormatter.format("`行内码`"));
        assertEquals("一级标题", WeChatTextFormatter.format("# 一级标题"));
        assertEquals("三级标题", WeChatTextFormatter.format("### 三级标题"));
    }

    @Test
    void shouldRewriteLinkToTextWithUrl() {
        assertEquals("百度(https://baidu.com)",
            WeChatTextFormatter.format("[百度](https://baidu.com)"));
    }

    @Test
    void shouldStripCodeFencesButKeepContent() {
        String raw = "示例：\n```java\nSystem.out.println(1);\n```\n结束";

        String formatted = WeChatTextFormatter.format(raw);

        assertFalse(formatted.contains("```"));
        assertTrue(formatted.contains("System.out.println(1);"));
        assertTrue(formatted.contains("结束"));
    }

    @Test
    void shouldKeepSingleNewlines() {
        assertEquals("第一行\n第二行", WeChatTextFormatter.format("第一行\n第二行"));
    }

    @Test
    void shouldHandleBlank() {
        assertEquals("", WeChatTextFormatter.format(null));
        assertEquals("", WeChatTextFormatter.format("   "));
    }
}
