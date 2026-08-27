package com.richard.fyoung.customeradmin.common.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 排序规则一致性门禁：回放四条建库路径的建表语句，断言每张表最终都落在 utf8mb4_unicode_ci。
 *
 * <p><b>为什么需要机器防线</b>：MySQL 8 下 {@code DEFAULT CHARSET=utf8mb4} 不带 COLLATE 时用的是
 * 字符集默认（utf8mb4_0900_ai_ci），而不是建库时指定的排序规则——写漏一个 COLLATE 不会报任何错，
 * 只在某天两张表 JOIN 字符串列时炸 1267。这个形状已经复发四次：V58 与 V97 各修一次表，
 * ModelImpactMapper 用 COLLATE 字面量绕一次，BusinessOutcomeMapper 用 CAST(x AS BINARY) 绕一次
 * （代价是 session_id 索引失效）。人工评审挡不住，因为漏写的那一行看起来和正确的一模一样。</p>
 *
 * <p><b>为什么放在 admin 模块而不是各模块一份</b>：这是跨两个库、跨迁移与镜像四条路径的仓库级不变式，
 * 拆成两份就要把回放逻辑抄两遍——那正是本次要消灭的「多个真相来源」。admin 侧已有读仓库根的
 * contract test 先例，镜像同步约定也在这里落地，故统一收在此处。starter 侧新增 cw 迁移若漏写
 * COLLATE，会在全量构建时由本用例报红。</p>
 *
 * <p><b>四条路径必须一起断言</b>：Flyway 与完整镜像此前对 37 张 cw_* 表给出不同排序规则
 * （迁移写 {@code DEFAULT CHARSET=utf8mb4}、镜像写 {@code COLLATE utf8mb4_unicode_ci}），
 * 于是同一张表的排序规则取决于这个库当初是怎么建的。只断言一条路径挡不住这种分歧。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@DisplayName("建表排序规则一致性门禁")
class TableCollationAlignmentContractTest {

    /** 两库 CREATE DATABASE 均声明此排序规则，V22/V100 也收敛于此。 */
    private static final String TARGET_COLLATION = "utf8mb4_unicode_ci";

    /** MySQL 8 的字符集默认值：写了 CHARSET 却不写 COLLATE 时实际生效的就是它。 */
    private static final Map<String, String> CHARSET_DEFAULT_COLLATION = Map.of(
        "utf8mb4", "utf8mb4_0900_ai_ci",
        "utf8", "utf8mb3_general_ci",
        "utf8mb3", "utf8mb3_general_ci");

    private static final Pattern CREATE_TABLE = Pattern.compile(
        "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([A-Za-z0-9_]+)`?\\s*\\(",
        Pattern.CASE_INSENSITIVE);
    /** 兼容裸 ALTER 与 V22/V100 那种包在预处理语句字符串字面量里的写法。 */
    private static final Pattern ALTER_CONVERT = Pattern.compile(
        "ALTER\\s+TABLE\\s+`?([A-Za-z0-9_]+)`?\\s+CONVERT\\s+TO\\s+CHARACTER\\s+SET\\s+"
            + "([A-Za-z0-9_]+)\\s*(?:COLLATE\\s+([A-Za-z0-9_]+))?",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_TABLE = Pattern.compile(
        "DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?`?([A-Za-z0-9_]+)`?", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_OPTION_CHARSET = Pattern.compile(
        "(?:DEFAULT\\s+)?CHAR(?:ACTER)?\\s*SET\\s*=?\\s*([A-Za-z0-9_]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_OPTION_COLLATE = Pattern.compile(
        "COLLATE\\s*=?\\s*([A-Za-z0-9_]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_VERSION = Pattern.compile("^(?:V)?(\\d+)[-_]");

    @Test
    @DisplayName("admin 库：Flyway 迁移逐版执行后，每张表都是 utf8mb4_unicode_ci")
    void adminMigrationsShouldEndAtTargetCollation() throws IOException {
        assertAllTablesAligned("customer_admin / Flyway 迁移",
            sortedSqlFiles(repositoryRoot().resolve(
                "customer-admin-server/src/main/resources/db/migration")));
    }

    @Test
    @DisplayName("admin 库：mysql/02 手工镜像建出的表也都是 utf8mb4_unicode_ci")
    void adminSchemaMirrorShouldEndAtTargetCollation() throws IOException {
        assertAllTablesAligned("customer_admin / mysql/02 镜像",
            sortedSqlFiles(repositoryRoot().resolve("mysql/02-customer-admin")));
    }

    @Test
    @DisplayName("客服端库：Flyway 迁移逐版执行后，每张表都是 utf8mb4_unicode_ci")
    void customerWorkMigrationsShouldEndAtTargetCollation() throws IOException {
        assertAllTablesAligned("agent_scope_customer_work / Flyway 迁移",
            sortedSqlFiles(repositoryRoot().resolve(
                "customer-work-starter/src/main/resources/db/customerwork/migration")));
    }

    @Test
    @DisplayName("客服端库：mysql/01 完整镜像与增量脚本建出的表也都是 utf8mb4_unicode_ci")
    void customerWorkSchemaMirrorShouldEndAtTargetCollation() throws IOException {
        Path mirror = repositoryRoot().resolve("mysql/01-agent-scope-customer-work");
        List<Path> ordered = new ArrayList<>();
        ordered.add(mirror.resolve("customer-work-schema.sql"));
        try (Stream<Path> files = Files.list(mirror)) {
            files.filter(p -> p.getFileName().toString().endsWith("-alter.sql"))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .forEach(ordered::add);
        }
        assertAllTablesAligned("agent_scope_customer_work / mysql/01 镜像", ordered);
    }

    @Test
    @DisplayName("Flyway 路径与完整镜像必须对每张表给出相同排序规则")
    void migrationAndMirrorMustAgreePerTable() throws IOException {
        Path root = repositoryRoot();
        Map<String, String> viaFlyway = replay(sortedSqlFiles(root.resolve(
            "customer-work-starter/src/main/resources/db/customerwork/migration")));
        Path mirror = root.resolve("mysql/01-agent-scope-customer-work");
        List<Path> ordered = new ArrayList<>();
        ordered.add(mirror.resolve("customer-work-schema.sql"));
        try (Stream<Path> files = Files.list(mirror)) {
            files.filter(p -> p.getFileName().toString().endsWith("-alter.sql"))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .forEach(ordered::add);
        }
        Map<String, String> viaMirror = replay(ordered);

        List<String> divergent = new ArrayList<>();
        for (Map.Entry<String, String> entry : viaFlyway.entrySet()) {
            String fromMirror = viaMirror.get(entry.getKey());
            // 镜像额外含框架自建的 agentscope_sessions，迁移侧没有，属预期差异，只比对两侧都有的表。
            if (fromMirror != null && !fromMirror.equals(entry.getValue())) {
                divergent.add(String.format("%s: Flyway=%s 镜像=%s",
                    entry.getKey(), entry.getValue(), fromMirror));
            }
        }
        assertTrue(divergent.isEmpty(),
            "同一张表在两条建库路径下排序规则不一致，库最终长什么样将取决于当初怎么建的：\n  "
                + String.join("\n  ", divergent));
    }

    /** 回放建表与转换语句，断言终态全部落在目标排序规则。 */
    private void assertAllTablesAligned(String scope, List<Path> files) throws IOException {
        Map<String, String> finalState = replay(files);
        assertTrue(finalState.size() > 0, scope + " 没有解析到任何建表语句，扫描路径可能写错了");

        List<String> offenders = finalState.entrySet().stream()
            .filter(e -> !TARGET_COLLATION.equals(e.getValue()))
            .map(e -> e.getKey() + " -> " + e.getValue())
            .sorted()
            .toList();
        if (!offenders.isEmpty()) {
            fail(scope + " 存在排序规则不是 " + TARGET_COLLATION + " 的表共 " + offenders.size()
                + " 张。建表语句必须显式写 COLLATE " + TARGET_COLLATION
                + "——只写 DEFAULT CHARSET=utf8mb4 会静默落到 utf8mb4_0900_ai_ci：\n  "
                + String.join("\n  ", offenders));
        }
    }

    /** 按文件顺序回放，返回 表名 -> 最终排序规则。 */
    private Map<String, String> replay(List<Path> files) throws IOException {
        Map<String, String> tables = new LinkedHashMap<>();
        for (Path file : files) {
            String sql = stripComments(Files.readString(file, StandardCharsets.UTF_8));

            Matcher create = CREATE_TABLE.matcher(sql);
            while (create.find()) {
                int open = create.end() - 1;
                int close = matchingParen(sql, open);
                if (close < 0) {
                    continue;
                }
                int semicolon = sql.indexOf(';', close);
                String tail = sql.substring(close + 1, semicolon < 0 ? sql.length() : semicolon);
                // 同名表可能在多个脚本里 IF NOT EXISTS 重复出现，以首次定义为准。
                tables.putIfAbsent(create.group(1), resolveCollation(
                    firstGroup(TABLE_OPTION_CHARSET, tail), firstGroup(TABLE_OPTION_COLLATE, tail)));
            }

            Matcher convert = ALTER_CONVERT.matcher(sql);
            while (convert.find()) {
                String table = convert.group(1);
                if (tables.containsKey(table)) {
                    tables.put(table, resolveCollation(convert.group(2), convert.group(3)));
                }
            }

            Matcher drop = DROP_TABLE.matcher(sql);
            while (drop.find()) {
                tables.remove(drop.group(1));
            }
        }
        return tables;
    }

    /**
     * 还原一段建表选项实际生效的排序规则。
     *
     * <p>三种写法的结果不同，这正是本次要收口的根因：写了 COLLATE 用它；只写 CHARSET 用**字符集默认**
     * （不是建库参数）；两者都不写才继承建库的 {@link #TARGET_COLLATION}。</p>
     */
    private String resolveCollation(String charset, String collate) {
        if (collate != null) {
            return collate.toLowerCase();
        }
        if (charset != null) {
            return CHARSET_DEFAULT_COLLATION.getOrDefault(
                charset.toLowerCase(), "unknown-charset:" + charset.toLowerCase());
        }
        return TARGET_COLLATION;
    }

    private String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 从左括号找到配对右括号，跳过字符串字面量里的括号。 */
    private int matchingParen(String sql, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < sql.length(); i++) {
            char current = sql.charAt(i);
            if (quote != 0) {
                if (current == '\\') {
                    i++;
                } else if (current == quote) {
                    quote = 0;
                }
            } else if (current == '\'' || current == '"') {
                quote = current;
            } else if (current == '(') {
                depth++;
            } else if (current == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private String stripComments(String sql) {
        String withoutBlocks = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder builder = new StringBuilder(withoutBlocks.length());
        for (String line : withoutBlocks.split("\n", -1)) {
            int marker = line.indexOf("--");
            builder.append(marker >= 0 ? line.substring(0, marker) : line).append('\n');
        }
        return builder.toString();
    }

    /** 按版本号数值排序，避免 V10 排在 V2 前面这种字典序陷阱。 */
    private List<Path> sortedSqlFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files
                .filter(path -> path.getFileName().toString().endsWith(".sql"))
                .sorted(Comparator.comparingInt(this::versionOf)
                    .thenComparing(path -> path.getFileName().toString()))
                .toList();
        }
    }

    private int versionOf(Path path) {
        Matcher matcher = LEADING_VERSION.matcher(path.getFileName().toString());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }

    private Path repositoryRoot() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        return Files.isDirectory(workingDirectory.resolve("mysql"))
            ? workingDirectory : workingDirectory.getParent();
    }
}
