package com.richard.fyoung.customerwork.infra.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 * <p>可选的<b>系统种子数据段</b>见 {@link #export(Connection, String, boolean)}：只有迁移本身
 * 写入的行才会出现在那里，同样由迁移产物决定，不需要另一份人工维护的清单。</p>
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

    /** 种子段的语句前缀：数据段整体排在全部建表语句之后，只认建表前缀会把这里的差异
     *  一律归到最后一张表上——指着一张毫不相干的表报错，比不报表名更难查。 */
    private static final String INSERT_INTO_PREFIX = "INSERT INTO `";

    private static final String SECTION_RULE =
        "-- ----------------------------------------------------------------------------";

    /** 数据段的小节标题后缀，与结构段的同名小节区分开。 */
    private static final String SEED_SECTION_SUFFIX = " · 系统种子数据";

    private static final String LIST_TABLES_SQL =
        "SELECT table_name FROM information_schema.tables "
            + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' ORDER BY table_name";

    private static final String LIST_COLUMNS_SQL =
        "SELECT column_name, column_default, extra, data_type FROM information_schema.columns "
            + "WHERE table_schema = DATABASE() AND table_name = ? ORDER BY ordinal_position";

    private static final String LIST_PRIMARY_KEY_SQL =
        "SELECT column_name FROM information_schema.statistics "
            + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = 'PRIMARY' "
            + "ORDER BY seq_in_index";

    /**
     * 值由导出时刻决定的自动列标记。
     *
     * <p>种子行的 {@code create_time}/{@code update_time} 取的是迁移执行那一秒，每次重新生成快照
     * 都不一样。照原样写进 INSERT，门禁会变成「每跑必红」——而红的原因与任何人的改动都无关，
     * 几次之后就没人再信它了。这类列一律不进 INSERT 列清单，由建库时的
     * {@code DEFAULT CURRENT_TIMESTAMP} 现场填充，语义上也正是「这一行是建库那一刻写入的」。</p>
     */
    private static final String GENERATED_DEFAULT_EXTRA = "DEFAULT_GENERATED";

    /** 与上面配套：只跳「默认值就是当前时间」的列，别的表达式默认值仍照常导出。 */
    private static final String CURRENT_TIMESTAMP_DEFAULT = "CURRENT_TIMESTAMP";

    /** 数值类型不加引号，保持与迁移脚本里的字面形态一致。 */
    private static final Set<String> NUMERIC_TYPES =
        Set.of("bigint", "int", "mediumint", "smallint", "tinyint", "decimal", "float", "double", "bit");

    /** 合法的库内标识符：库名、表名、列名都走这一处校验。 */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[a-z0-9_]+");

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
     * 导出当前连接所指库的全量表结构（不含任何数据行）。
     *
     * @param connection 指向已完成迁移的库的连接
     * @param header     文件头文本，由调用方给出（不同库的说明与执行方式不同），需自带结尾换行
     * @return 归一化后的完整快照文本
     */
    public static String export(Connection connection, String header) throws SQLException {
        return export(connection, header, false);
    }

    /**
     * 导出当前连接所指库的全量表结构，并可附带迁移写入的系统种子数据。
     *
     * <p>带上数据段之后，快照单文件执行完就是一个<b>可直接登录使用</b>的库：菜单权限树、角色、
     * 默认租户与 admin 账号都在里面。此前的纯结构快照虽然自称能「全新建库」，建出来却是一个谁也
     * 进不去的空壳——库建好了，登录页过不去。</p>
     *
     * <p>导哪些行不由人工清单决定，而是<b>照实导出迁移产物里的全部数据行</b>。挑子集会让快照与
     * 迁移产物不再等价，两条建库路径给出不同结果——那正是本仓库反复踩过的形状（表排序规则那次）。
     * 演示/示例数据的清理有 {@code scripts/clear-demo-data.sh} 专职负责，不在这里另立一套判定。</p>
     *
     * @param connection      指向已完成迁移的库的连接
     * @param header          文件头文本，需自带结尾换行
     * @param includeSeedData 是否在结构之后追加种子数据段
     * @return 归一化后的完整快照文本
     */
    public static String export(Connection connection, String header, boolean includeSeedData)
        throws SQLException {
        List<String> tables = listBaseTables(connection);
        StringBuilder snapshot = new StringBuilder(header);
        for (String table : tables) {
            snapshot.append(LINE_SEPARATOR)
                .append(SECTION_RULE).append(LINE_SEPARATOR)
                .append("-- ").append(table).append(LINE_SEPARATOR)
                .append(SECTION_RULE).append(LINE_SEPARATOR)
                .append(normalize(showCreateTable(connection, table)))
                .append(';').append(LINE_SEPARATOR);
        }
        if (includeSeedData) {
            appendSeedData(snapshot, connection, tables);
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

    /** 从差异位置往上找最近的建表或种子插入语句，把差异落到具体某张表上。 */
    private static String tableHint(String[] lines, int index) {
        for (int cursor = Math.min(index, lines.length - 1); cursor >= 0; cursor--) {
            String line = lines[cursor];
            String prefix = line.startsWith(CREATE_TABLE_PREFIX) ? CREATE_TABLE_PREFIX
                : line.startsWith(INSERT_INTO_PREFIX) ? INSERT_INTO_PREFIX : null;
            if (prefix != null) {
                String remainder = line.substring(prefix.length());
                int end = remainder.indexOf('`');
                if (end > 0) {
                    return "（表 " + remainder.substring(0, end)
                        + (INSERT_INTO_PREFIX.equals(prefix) ? " 的种子数据" : "") + "）";
                }
                return "";
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

    /**
     * 追加种子数据段：逐表导出迁移写入的行，空表跳过。
     *
     * <p>表序沿用结构段的表名序。本库没有外键约束（导出时已实测为 0 条），INSERT 之间不存在
     * 先后依赖；哪天真加了外键，这里要改成按依赖拓扑排序，否则建库会在约束上失败。</p>
     */
    private static void appendSeedData(StringBuilder snapshot, Connection connection, List<String> tables)
        throws SQLException {
        for (String table : tables) {
            List<SeedColumn> columns = seedColumns(connection, table);
            if (columns.isEmpty()) {
                continue;
            }
            String rows = selectSeedRows(connection, table, columns);
            if (rows.isEmpty()) {
                continue;
            }
            snapshot.append(LINE_SEPARATOR)
                .append(SECTION_RULE).append(LINE_SEPARATOR)
                .append("-- ").append(table).append(SEED_SECTION_SUFFIX).append(LINE_SEPARATOR)
                .append(SECTION_RULE).append(LINE_SEPARATOR)
                .append("INSERT INTO ").append(quoted(table)).append(" (")
                .append(columnList(columns)).append(") VALUES").append(LINE_SEPARATOR)
                .append(rows).append(';').append(LINE_SEPARATOR);
        }
    }

    /** 取该表要写进 INSERT 的列，剔除值由导出时刻决定的自动时间列。 */
    private static List<SeedColumn> seedColumns(Connection connection, String table) throws SQLException {
        List<SeedColumn> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(LIST_COLUMNS_SQL)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String name = resultSet.getString(1);
                    String columnDefault = resultSet.getString(2);
                    String extra = resultSet.getString(3);
                    String dataType = resultSet.getString(4);
                    if (generatedFromExportMoment(columnDefault, extra)) {
                        continue;
                    }
                    columns.add(new SeedColumn(name, NUMERIC_TYPES.contains(lowerCase(dataType))));
                }
            }
        }
        return columns;
    }

    /** 判定「这一列的值取自导出那一刻」：默认值是当前时间，且由 MySQL 自动生成。 */
    private static boolean generatedFromExportMoment(String columnDefault, String extra) {
        if (columnDefault == null || !lowerCase(columnDefault).startsWith(lowerCase(CURRENT_TIMESTAMP_DEFAULT))) {
            return false;
        }
        return extra != null && lowerCase(extra).contains(lowerCase(GENERATED_DEFAULT_EXTRA));
    }

    /** 按确定顺序读出该表全部行，拼成 VALUES 列表；空表返回空串。 */
    private static String selectSeedRows(Connection connection, String table, List<SeedColumn> columns)
        throws SQLException {
        String sql = "SELECT " + columnList(columns) + " FROM " + quoted(table)
            + " ORDER BY " + orderBy(connection, table, columns);
        StringBuilder rows = new StringBuilder();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                if (rows.length() > 0) {
                    rows.append(',').append(LINE_SEPARATOR);
                }
                rows.append("  (");
                for (int index = 0; index < columns.size(); index++) {
                    if (index > 0) {
                        rows.append(", ");
                    }
                    rows.append(literal(resultSet.getString(index + 1), columns.get(index)));
                }
                rows.append(')');
            }
        }
        return rows.toString();
    }

    /**
     * 行序必须确定，否则同一套迁移两次导出的 VALUES 顺序可能不同，门禁会红在一个假差异上。
     *
     * <p>优先按主键排；没有主键的表退化为按全部导出列排——只要值一样，顺序就一样。</p>
     */
    private static String orderBy(Connection connection, String table, List<SeedColumn> columns)
        throws SQLException {
        List<String> primaryKey = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(LIST_PRIMARY_KEY_SQL)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    primaryKey.add(quoted(resultSet.getString(1)));
                }
            }
        }
        return primaryKey.isEmpty() ? columnList(columns) : String.join(", ", primaryKey);
    }

    /** 单个值的 SQL 字面量：NULL 直出，数值不加引号，其余转义后加引号。 */
    private static String literal(String value, SeedColumn column) {
        if (value == null) {
            return "NULL";
        }
        return column.numeric() ? value : "'" + escape(value) + "'";
    }

    /**
     * 按 MySQL 字面量规则转义。
     *
     * <p>换行与制表符转成转义序列而不是原样保留：一行 VALUES 对应一行文本，
     * {@link #describeDifference} 才能把差异定位到具体某一行，人也才看得动 diff。</p>
     */
    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '\'' -> escaped.append("\\'");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\0' -> escaped.append("\\0");
                case 0x1A -> escaped.append("\\Z");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private static String columnList(List<SeedColumn> columns) {
        StringBuilder list = new StringBuilder();
        for (SeedColumn column : columns) {
            if (list.length() > 0) {
                list.append(", ");
            }
            list.append(quoted(column.name()));
        }
        return list.toString();
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
        if (!SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("illegal database identifier: " + identifier);
        }
        return "`" + identifier + "`";
    }

    private static String lowerCase(String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    /**
     * 参与种子导出的一列。
     *
     * @param name    列名
     * @param numeric 是否数值类型（决定字面量加不加引号）
     */
    private record SeedColumn(String name, boolean numeric) {
    }
}
