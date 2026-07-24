package com.richard.fyoung.customerchannel.access.wechat;

import com.richard.fyoung.customerchannel.access.support.MarkdownTables;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 微信客服消息文本降级格式化器。
 *
 * <p>微信客服消息是<b>纯文本</b>（不渲染任何 markdown），故把智能体的标准 markdown 降级为纯文本：</p>
 * <ul>
 *   <li>表格 → 「表头: 值」列表（复用 {@link MarkdownTables}，与钉钉共享）；</li>
 *   <li>代码围栏 {@code ```} 行剥掉，围栏内内容原样保留；</li>
 *   <li>剥 {@code **加粗**} / {@code `行内码`} / 行首 {@code #} 标题符；</li>
 *   <li>链接 {@code [文本](url)} → {@code 文本(url)}；</li>
 *   <li>换行原样保留（微信认 {@code \n}，不像钉钉需要补双换行）。</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
final class WeChatTextFormatter {

    /** 链接：[文本](url) → 文本(url)。 */
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\(([^)]*)\\)");
    /** 加粗：**文本** / __文本__ → 文本。 */
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*|__([^_]+)__");
    /** 行内码：`文本` → 文本。 */
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    /** 行首标题符：# ~ ###### 。 */
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s*");

    private WeChatTextFormatter() {
    }

    /** 把标准 markdown 降级为微信可读的纯文本。 */
    static String format(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        List<String> out = new ArrayList<>();
        List<String> tableBlock = new ArrayList<>();
        for (String line : raw.split("\n", -1)) {
            if (MarkdownTables.isTableLine(line)) {
                tableBlock.add(line.trim());
                continue;
            }
            flushTable(tableBlock, out);
            // 代码围栏行剥掉，围栏内内容原样保留（不做 inline 处理，避免误伤代码里的符号）
            if (line.trim().startsWith("```")) {
                continue;
            }
            out.add(stripInline(line));
        }
        flushTable(tableBlock, out);
        // 微信认单换行，原样以 \n 连接（保留空行）
        return String.join("\n", out);
    }

    private static void flushTable(List<String> tableBlock, List<String> out) {
        if (tableBlock.isEmpty()) {
            return;
        }
        out.addAll(MarkdownTables.flatten(tableBlock));
        tableBlock.clear();
    }

    /** 剥掉行内 markdown 修饰，仅保留可读文本。 */
    private static String stripInline(String line) {
        String s = HEADING.matcher(line).replaceFirst("");
        s = LINK.matcher(s).replaceAll("$1($2)");
        s = BOLD.matcher(s).replaceAll(matchResult ->
            matchResult.group(1) != null ? matchResult.group(1) : matchResult.group(2));
        s = INLINE_CODE.matcher(s).replaceAll("$1");
        return s;
    }
}
