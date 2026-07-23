package com.richard.fyoung.customerchannel.access.dingtalk;

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
 *   <li>表格 → 每行数据转成「- 表头1: 值1，表头2: 值2」的列表（分隔行丢弃）；</li>
 *   <li>代码围栏 {@code ```} 行剥掉（保留围栏内内容）；</li>
 *   <li>行与行之间补成双换行（钉钉才认），连续空行折叠成一个段落间隔。</li>
 * </ul>
 * <p>仅钉钉需要，故放 dingtalk 包内；其他渠道有各自的渲染能力时各自处理。</p>
 * @author owlzhangfq@gmail.com
 */
final class DingTalkMarkdownFormatter {

    private static final String CELL_SEPARATOR = "，";
    private static final String KEY_VALUE_SEPARATOR = ": ";

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
            if (isTableLine(line)) {
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

    /** 表格行判定：trim 后以 | 开头（宽松匹配，避免漏网导致语法原样露出）。 */
    private static boolean isTableLine(String line) {
        return line != null && line.trim().startsWith("|");
    }

    /** 分隔行判定：只含 | - : 和空白（如 |---|:---:|）。 */
    private static boolean isSeparatorLine(String line) {
        return line.matches("\\|?[\\s:\\-|]+\\|?") && line.contains("-");
    }

    /** 把攒下的表格块转成「- 表头: 值」列表追加到输出（无表头结构时退化为按空格拼接单元格）。 */
    private static void flushTable(List<String> tableBlock, List<String> out) {
        if (tableBlock.isEmpty()) {
            return;
        }
        List<List<String>> rows = new ArrayList<>();
        for (String line : tableBlock) {
            if (isSeparatorLine(line)) {
                continue;
            }
            rows.add(splitCells(line));
        }
        tableBlock.clear();
        if (rows.isEmpty()) {
            return;
        }
        // 首行视作表头：数据行逐列输出「表头: 值」；只有一行时直接输出该行单元格
        List<String> headers = rows.get(0);
        if (rows.size() == 1) {
            out.add("- " + String.join(CELL_SEPARATOR, headers));
            return;
        }
        for (int r = 1; r < rows.size(); r++) {
            List<String> cells = rows.get(r);
            StringBuilder sb = new StringBuilder("- ");
            for (int c = 0; c < cells.size(); c++) {
                if (c > 0) {
                    sb.append(CELL_SEPARATOR);
                }
                if (c < headers.size() && StringUtils.hasText(headers.get(c))) {
                    sb.append(headers.get(c)).append(KEY_VALUE_SEPARATOR);
                }
                sb.append(cells.get(c));
            }
            out.add(sb.toString());
        }
    }

    private static List<String> splitCells(String line) {
        String body = line.trim();
        if (body.startsWith("|")) {
            body = body.substring(1);
        }
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : body.split("\\|", -1)) {
            cells.add(cell.trim());
        }
        return cells;
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
