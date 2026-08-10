package com.richard.fyoung.customerwork.infra.sqlkit;

import java.util.regex.Pattern;

/**
 * 只读 SQL 校验器：保存/执行 SQL 文本前调用一次（SQL 文本层唯一的防御点，fast-fail）。
 *
 * <p>安全边界要求"强制只读 SELECT、无手写即席执行"。本校验器与执行层的 {@code readOnly=true}
 * 连接构成双保险——这里做文本层拦截（去注释后首关键字必须 SELECT/WITH、拒绝多语句），
 * 不引 JSqlParser 做完整语法树解析（对只读判定过度工程，正则+词法足够）。</p>
 *
 * <p>校验失败统一抛 {@link IllegalArgumentException}（starter 不感知任何业务错误码体系）；
 * 需要业务错误码的调用方在自己的唯一入口处捕获并转译一次即可，不要层层包装。</p>
 * @author owlzhangfq@gmail.com
 */
public final class SqlValidator {

    /** 块注释 /* ... *\/（非贪婪，跨行）。 */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    /** 行注释：-- 或 # 到行尾。 */
    private static final Pattern LINE_COMMENT = Pattern.compile("(--|#)[^\\n]*");

    private SqlValidator() {
    }

    /**
     * 校验为只读单语句查询，非法直接抛 {@link IllegalArgumentException}。
     * 步骤：剥离注释 → trim → 去掉结尾唯一分号 → 拒绝内部分号（多语句）→ 首关键字必须 SELECT/WITH。
     */
    public static void validateReadOnly(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL 语句不能为空");
        }
        String stripped = LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(sql).replaceAll(" ")).replaceAll(" ").trim();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException("SQL 语句（去注释后）不能为空");
        }
        // 允许结尾一个分号，校验多语句前先去掉
        if (stripped.endsWith(";")) {
            stripped = stripped.substring(0, stripped.length() - 1).trim();
        }
        if (stripped.contains(";")) {
            throw new IllegalArgumentException("SQL 不允许多语句（含分号）");
        }
        String firstWord = firstWord(stripped);
        if (!"SELECT".equalsIgnoreCase(firstWord) && !"WITH".equalsIgnoreCase(firstWord)) {
            throw new IllegalArgumentException("仅允许只读 SELECT/WITH 查询，当前首关键字: " + firstWord);
        }
    }

    private static String firstWord(String sql) {
        int i = 0;
        while (i < sql.length() && !Character.isWhitespace(sql.charAt(i)) && sql.charAt(i) != '(') {
            i++;
        }
        return sql.substring(0, i);
    }
}
