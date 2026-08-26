package com.richard.fyoung.customeradmin.common.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>审计字段填充策略门禁</b>：{@code createBy} / {@code createTime} 只能 {@code INSERT}，
 * {@code updateBy} / {@code updateTime} 只能 {@code INSERT_UPDATE}。
 *
 * <p><b>为什么只管策略、不抽基类</b>：审计字段确实在几十个实体里各写一遍，但那属于"长得像"
 * 而不是"同一个真相"——加一个新实体不需要改动其余 41 个，不满足项目判定重复的标准
 * （改一处必须同步改 N 处、且漏改不报错）。更要紧的是这些实体的审计形状<b>本就不同</b>：
 * 版本/修订类表（{@code ai_knowledge_base_version}、{@code ai_skill_version}、
 * {@code ai_config_version} 等）是追加写、从不更新，因此只有 create 侧、表里压根没有
 * {@code update_by} / {@code update_time} 列。强行让它们继承一个四字段基类，
 * MyBatis-Plus 会生成引用不存在列的 SQL。</p>
 *
 * <p><b>真正会静默出事的是策略写反</b>：{@code createBy} 若标成 {@code INSERT_UPDATE}，
 * 每次更新都会把创建人覆盖成当前操作人——编译不报错、测试不变红、页面照常显示一个人名，
 * 只是那个人名从此是最后改的人。等到要追溯"这条配置最初是谁建的"时，数据已经没了。
 * 本测试把两组策略钉死。</p>
 *
 * <p>另有一批实体（{@code ai_agent_memory}、{@code ai_model_health_snapshot} 等）不标注解，
 * 由建表语句的 {@code DEFAULT CURRENT_TIMESTAMP} / {@code ON UPDATE CURRENT_TIMESTAMP} 填充。
 * 那是另一种正当写法，本测试<b>不</b>强制它们改用注解——只要求"一旦标了注解，策略就必须对"。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class AuditFieldFillAlignmentTest {

    private static final String ADMIN_SOURCE_ROOT = "customer-admin-server/src/main/java";

    /** 审计字段 -> 唯一允许的填充策略。 */
    private static final Map<String, String> REQUIRED_FILL = Map.of(
        "createBy", "INSERT",
        "createTime", "INSERT",
        "updateBy", "INSERT_UPDATE",
        "updateTime", "INSERT_UPDATE");

    /** 实体的判据：MyBatis-Plus 的 {@code @TableName}。VO / DTO 不该有 fill 注解，故排除在外。 */
    private static final Pattern ENTITY_MARKER = Pattern.compile("@TableName\\s*\\(");

    @Test
    @DisplayName("审计字段的 fill 策略不得写反：createBy 标成 INSERT_UPDATE 会静默覆盖创建人")
    void auditFieldFillStrategyMustBeConsistent() throws IOException {
        List<String> problems = new ArrayList<>();
        int checked = 0;

        for (Path file : entitySources()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            for (Map.Entry<String, String> e : REQUIRED_FILL.entrySet()) {
                String field = e.getKey();
                String required = e.getValue();
                Matcher m = Pattern.compile(
                        "((?:@TableField\\([^)]*\\)\\s*)?)private\\s+(?:Long|LocalDateTime)\\s+"
                            + field + "\\s*;")
                    .matcher(source);
                if (!m.find()) {
                    continue;
                }
                Matcher fill = Pattern.compile("FieldFill\\.(\\w+)").matcher(m.group(1));
                if (!fill.find()) {
                    // 未标注解：由建表语句的 DEFAULT CURRENT_TIMESTAMP 填充，是另一种正当写法
                    continue;
                }
                checked++;
                if (!required.equals(fill.group(1))) {
                    problems.add(file.getFileName() + "#" + field
                        + " 的 fill 是 FieldFill." + fill.group(1) + "，应为 FieldFill." + required);
                }
            }
        }

        if (checked == 0) {
            fail("未扫描到任何带 fill 注解的审计字段，测试的定位逻辑可能已失效");
        }
        if (!problems.isEmpty()) {
            fail("审计字段填充策略不一致，共 " + problems.size() + " 处：\n  - "
                + String.join("\n  - ", problems)
                + "\ncreateBy / createTime 只能 INSERT；updateBy / updateTime 只能 INSERT_UPDATE。"
                + "\n把 createBy 标成 INSERT_UPDATE 会让每次更新覆盖创建人，且全程不报错。");
        }
    }

    private static List<Path> entitySources() throws IOException {
        Path root = resolveModulePath(ADMIN_SOURCE_ROOT);
        if (!Files.exists(root)) {
            fail("找不到 admin 源码目录：" + root.toAbsolutePath());
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .filter(p -> {
                    try {
                        return ENTITY_MARKER.matcher(
                            Files.readString(p, StandardCharsets.UTF_8)).find();
                    } catch (IOException e) {
                        throw new IllegalStateException("读取源文件失败：" + p, e);
                    }
                })
                .sorted()
                .toList();
        }
    }

    /** 兼容两种工作目录：仓库根（多模块构建）与模块目录（IDE 单模块跑测试）。 */
    private static Path resolveModulePath(String moduleRelative) {
        Path fromRepoRoot = Paths.get(moduleRelative);
        return Files.exists(fromRepoRoot) ? fromRepoRoot : Paths.get("..").resolve(moduleRelative);
    }
}
