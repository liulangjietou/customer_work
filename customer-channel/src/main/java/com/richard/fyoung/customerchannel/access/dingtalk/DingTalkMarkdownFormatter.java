package com.richard.fyoung.customerchannel.access.dingtalk;

import com.richard.fyoung.customerchannel.access.support.MarkdownTables;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 钉钉 markdown 降级格式化器。
 *
 * <p>钉钉 markdown 消息只支持很小的子集（标题/加粗/斜体/链接/图片/列表/引用），**不支持表格与代码块**，
 * 且单个换行不产生折行。智能体回复是标准 markdown，直接下发会把 {@code |---|} 等表格语法原样露出。
 * 本类做渠道侧降级：</p>
 * <ul>
 *   <li>表格 → 每行数据转成「- 表头1: 值1，表头2: 值2」的列表（复用 {@link MarkdownTables}）；</li>
 *   <li>代码围栏 {@code ```} 行剥掉（保留围栏内内容）；</li>
 *   <li>行与行之间补成双换行（钉钉才认），连续空行折叠成一个段落间隔。</li>
 * </ul>
 * <p>换行策略是钉钉专属（补双换行），故留在 dingtalk 包内；表格拍平这类跨渠道通用逻辑已下沉 support。</p>
 * @author owlzhangfq@gmail.com
 */
final class DingTalkMarkdownFormatter {

    private DingTalkMarkdownFormatter() {
    }

    /** 把标准 markdown 降级为钉钉可渲染的 markdown。 */
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
            String trimmedRight = line.stripTrailing();
            // 代码围栏行剥掉，围栏内内容原样保留
            if (trimmedRight.trim().startsWith("```")) {
                continue;
            }
            out.add(trimmedRight);
        }
        flushTable(tableBlock, out);
        return joinWithParagraphBreaks(out);
    }

    /** 把攒下的表格块拍平追加到输出，并清空缓冲。 */
    private static void flushTable(List<String> tableBlock, List<String> out) {
        if (tableBlock.isEmpty()) {
            return;
        }
        out.addAll(MarkdownTables.flatten(tableBlock));
        tableBlock.clear();
    }

    /** 非空行之间补双换行（钉钉单换行不折行），连续空行折叠。 */
    private static String joinWithParagraphBreaks(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (!StringUtils.hasText(line)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(line);
        }
        return sb.toString();
    }
}
