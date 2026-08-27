package com.richard.fyoung.customerwork.infra.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 全量表结构快照导出器：把一个已迁移到目标版本的库导成可复现的 DDL 文本。
 *
 * <p>不用 mysqldump 是因为它的输出带自增当前值、工具版本注释与转储时间，同一套迁移每次导出的
 * 结果都不同，没法拿来做漂移比对。这里逐表取 {@code SHOW CREATE TABLE} 再抹掉随数据变化的部分，
 * 保证「同一套迁移 + 同样建库参数」在任意时刻导出的文本逐字一致——快照文件与门禁测试才能共用
 * 同一段生成逻辑，不会各自漂移。</p>
 *
 * <p>导出源必须是<b>按仓库标准流程新建并跑完迁移的临时库</b>，不能是开发机上的长期业务库：后者
 * 沉积了并行分支试跑过的结构，快照会把这些沉积物一并固化，且没有任何机制能发现。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class SchemaSnapshotExporter {

    /** Flyway 自身的版本记录表：结构与业务无关，内容随执行历史变化，不进快照。 */
    private static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";

    /** 自增当前值随写入量变化，是快照里最主要的噪音来源。 */
    private static final Pattern AUTO_INCREMENT_VALUE = Pattern.compile(" AUTO_INCREMENT=\\d+");

    /** 快照固定用 LF，避免不同平台检出后 diff 全红。 */
    private static final String LINE_SEPARATOR = "\n";

    /** 建表语句前缀：用于把差异行回溯到所属表。 */
    private static final String CREATE_TABLE_PREFIX = "CREATE TABLE `";

    private static final String SECTION_RULE =
        "-- ----------------------------------------------------------------------------";

    private static final String LIST_TABLES_SQL =
        "SELECT table_name FROM information_schema.tables "
            + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' ORDER BY table_name";

    /**
     * 写入模式开关：置 true 时由生成侧覆写仓库内的快照文件，否则只做漂移比对。
     *
     * <p>两个库的快照测试共用这一个属性名。名字分散写会出现「脚本只刷新了一个库、另一个库悄悄留在
     * 旧版本」，而两边都不报错。</p>
     */
    public static final String WRITE_MODE_PROPERTY = "schema.snapshot.write";

    private SchemaSnapshotExporter() {
    }

    /** 当前是否处于快照写入模式（由 scripts/export-schema-snapshot.sh 传入）。 */
    public static boolean writeModeEnabled() {
        return Boolean.getBoolean(WRITE_MODE_PROPERTY);
    }

    /**
     * 导出当前连接所指库的全量表结构。
     *
     * @param connection 指向已完成迁移的库的连接
     * @param header     文件头文本，由调用方给出（不同库的说明与执行方式不同），需自带结尾换行
     * @return 归一化后的完整快照文本
     */
    public static String export(Connection connection, String header) throws SQLException {
        StringBuilder snapshot = new StringBuilder(header);
        for (String table : listBaseTables(connection)) {
            snapshot.append(LINE_SEPARATOR)
                .append(SECTION_RULE).append(LINE_SEPARATOR)
                .append("-- ").append(table).append(LINE_SEPARATOR)
                .append(SECTION_RULE).append(LINE_SEPARATOR)
                .append(normalize(showCreateTable(connection, table)))
                .append(';').append(LINE_SEPARATOR);
        }
        return snapshot.toString();
    }

    /**
     * 比对仓库内快照与本次导出产物，一致返回 {@code null}，否则返回定位到行的差异描述。
     *
     * <p>不能直接把两份全文丢给 assertEquals：快照有五万到十几万字符，断言失败时整篇打进报告里，
     * 人根本看不出改了哪张表的哪一列，只能自己 diff 一遍。这里直接给出第一处差异的行号、所属表
     * 与两侧内容，看一眼就知道是漏刷新了还是迁移真的变了。</p>
     *
     * @param expected 仓库内快照文件内容
     * @param actual   本次从临时库导出的产物
     */
    public static String describeDifference(String expected, String actual) {
        if (expected.equals(actual)) {
            return null;
        }
        String[] expectedLines = expected.split(LINE_SEPARATOR, -1);
        String[] actualLines = actual.split(LINE_SEPARATOR, -1);
        int commonLines = Math.min(expectedLines.length, actualLines.length);
        for (int index = 0; index < commonLines; index++) {
            if (!expectedLines[index].equals(actualLines[index])) {
                return "第 " + (index + 1) + " 行不一致" + tableHint(expectedLines, index) + LINE_SEPARATOR
                    + "  快照文件：" + expectedLines[index] + LINE_SEPARATOR
                    + "  迁移产物：" + actualLines[index];
            }
        }
        boolean actualIsLonger = actualLines.length > expectedLines.length;
        String[] extraLines = actualIsLonger ? actualLines : expectedLines;
        String side = actualIsLonger ? "迁移产物" : "快照文件";
        return "前 " + commonLines + " 行一致，" + side + "多出 "
            + (extraLines.length - commonLines) + " 行" + tableHint(extraLines, commonLines) + LINE_SEPARATOR
            + "  首行内容：" + extraLines[commonLines];
    }

    /** 从差异位置往上找最近的建表语句，把差异落到具体某张表上。 */
    private static String tableHint(String[] lines, int index) {
        for (int cursor = Math.min(index, lines.length - 1); cursor >= 0; cursor--) {
            if (lines[cursor].startsWith(CREATE_TABLE_PREFIX)) {
                String remainder = lines[cursor].substring(CREATE_TABLE_PREFIX.length());
                int end = remainder.indexOf('`');
                return end > 0 ? "（表 " + remainder.substring(0, end) + "）" : "";
            }
        }
        return "（文件头）";
    }

    /** 列出参与快照的业务表，按表名排序保证顺序稳定。 */
    private static List<String> listBaseTables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(LIST_TABLES_SQL);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String table = resultSet.getString(1);
                if (!FLYWAY_HISTORY_TABLE.equals(table)) {
                    tables.add(table);
                }
            }
        }
        return tables;
    }

    private static String showCreateTable(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SHOW CREATE TABLE " + quoted(table))) {
            if (!resultSet.next()) {
                throw new SQLException("table disappeared while exporting snapshot: " + table);
            }
            return resultSet.getString(2);
        }
    }

    /** 抹掉随数据变化的部分，只保留结构本身。 */
    private static String normalize(String ddl) {
        return AUTO_INCREMENT_VALUE.matcher(ddl).replaceAll("");
    }

    private static String quoted(String identifier) {
        if (!identifier.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("illegal database identifier: " + identifier);
        }
        return "`" + identifier + "`";
    }
}
