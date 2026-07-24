package com.richard.fyoung.customerchannel.access.support;

import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * markdown 表格降级为「表头: 值」列表的共享逻辑。
 *
 * <p>钉钉与微信客服消息都不渲染 markdown 表格（{@code |---|} 会原样露出），二者的 formatter 都需要把表格
 * 拍平成纯文本列表，故抽到接入层 {@code support} 包共享，避免复制粘贴。规则：首行视作表头，数据行逐列
 * 输出「表头: 值」，用「，」连接单元格；只有一行时直接输出该行单元格；分隔行（{@code |---|:---:|}）丢弃。</p>
 * @author owlzhangfq@gmail.com
 */
public final class MarkdownTables {

    /** 单元格之间的连接符。 */
    private static final String CELL_SEPARATOR = "，";
    /** 表头与值之间的连接符。 */
    private static final String KEY_VALUE_SEPARATOR = ": ";

    private MarkdownTables() {
    }

    /** 表格行判定：trim 后以 {@code |} 开头（宽松匹配，避免漏网导致语法原样露出）。 */
    public static boolean isTableLine(String line) {
        return line != null && line.trim().startsWith("|");
    }

    /** 分隔行判定：只含 {@code | - :} 和空白（如 {@code |---|:---:|}）。 */
    public static boolean isSeparatorLine(String line) {
        return line != null && line.matches("\\|?[\\s:\\-|]+\\|?") && line.contains("-");
    }

    /**
     * 把攒下的表格块（每行已 trim）拍平成「- 表头: 值」列表。
     *
     * @param tableBlock 连续的表格行（含表头、分隔行、数据行）
     * @return 拍平后的列表行；全为分隔行/空块时返回空列表（不修改入参）
     */
    public static List<String> flatten(List<String> tableBlock) {
        List<String> result = new ArrayList<>();
        if (CollectionUtils.isEmpty(tableBlock)) {
            return result;
        }
        List<List<String>> rows = new ArrayList<>();
        for (String line : tableBlock) {
            if (isSeparatorLine(line)) {
                continue;
            }
            rows.add(splitCells(line));
        }
        if (rows.isEmpty()) {
            return result;
        }
        // 首行视作表头：只有一行时直接输出该行单元格，否则数据行逐列输出「表头: 值」
        List<String> headers = rows.get(0);
        if (rows.size() == 1) {
            result.add("- " + String.join(CELL_SEPARATOR, headers));
            return result;
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
            result.add(sb.toString());
        }
        return result;
    }

    /** 拆分一行的单元格（去掉首尾 {@code |}，各单元格 trim）。 */
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
}
