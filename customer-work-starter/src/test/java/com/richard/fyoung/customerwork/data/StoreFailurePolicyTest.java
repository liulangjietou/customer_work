package com.richard.fyoung.customerwork.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>Store 失败处置门禁</b>：写操作失败必须向上传播，旁路写入才可以吞掉——而且必须写明理由。
 *
 * <p><b>这条约定本来就存在，只是没人守</b>：全仓 29 个 {@code Mybatis*Store} 共 114 处
 * {@code catch (Exception)}，实测分布是——写方法 24 处抛 / 14 处吞，读方法 45 处降级 / 15 处抛。
 * 即「写抛、读降」是主流写法，但被违反约三成，而且从调用点<b>看不出来这次会是哪种</b>。</p>
 *
 * <p><b>为什么盯写而不盯读</b>：读失败降级成空集合，最坏是少显示一些数据，页面上看得见；
 * 而写失败被吞掉是<b>数据没了却没有任何信号</b>。已经出过一次事：
 * {@code MybatisAgentCallLogStore#delete} 把 DB 异常和"行不存在"都返回 {@code false}，
 * 于是后台删除按钮在删除失败时照样提示"删除成功"（HTTP 200 + false，而前端并不看这个值）。</p>
 *
 * <p><b>读侧刻意不做强制</b>：降级是不是对的取决于调用方能不能分辨「失败」与「没数据」，
 * 那是逐个方法的判断（{@code MybatisSensitiveWordStore#findEnabled} 就特意返回
 * {@code Optional<List<>>} 来保住这个区别，好让过滤器 fail-closed）。
 * 机器判不了这件事，硬要判只会逼人写假注解。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class StoreFailurePolicyTest {

    private static final String STARTER_SOURCE_ROOT = "customer-work-starter/src/main/java";

    /** 方法名前缀判定为「写」。 */
    private static final Pattern WRITE_METHOD = Pattern.compile(
        "^(save|insert|update|delete|remove|upsert|append|record|increment|purge|clear|mark|bind|evict|put)");

    private static final Pattern METHOD_HEAD = Pattern.compile(
        "\\n    (?:public|private|protected)\\s+[\\w<>,\\[\\]. ]+?\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{");

    /** 错误码必须是可检索的「域-动作-FAIL」大写串；小写或自由文本在日志里根本搜不出来。 */
    private static final Pattern ERROR_CODE = Pattern.compile("^[A-Z0-9]+(-[A-Z0-9]+)+-FAIL$");

    private static final Pattern CODE_LITERAL = Pattern.compile("\"([A-Za-z0-9][A-Za-z0-9-]*)\"\\s*,");

    /**
     * 允许吞掉写失败的旁路写入：{@code 类名#方法名 } -> 理由。
     *
     * <p>共同点是<b>失败了不影响主链路，也不代表业务数据丢失</b>——埋点、观测、缓存、可重建的清理。
     * 新增条目必须在这里写明为什么它属于旁路；写不出理由的，说明它就该抛。</p>
     */
    private static final Set<String> SIDE_CHANNEL_WRITES = Set.of(
        // 用户反馈：非核心链路，失败不该阻断对话（该 Store 的 javadoc 已写明这条语义）
        "MybatisFeedbackStore#save",
        // 知识盲区埋点：统计用途，丢一条不影响回答
        "MybatisKnowledgeGapStore#recordMiss",
        // 提示词版本归因记录：指标归因用，丢一条只影响看板
        "MybatisPromptVersionStore#record",
        // 语义缓存：缓存本就允许失效，写不进去下次重算即可
        "MybatisSemanticCacheStore#save",
        "MybatisSemanticCacheStore#recordHit",
        "MybatisSemanticCacheStore#evictLeastRecentlyUsed",
        "MybatisSemanticCacheStore#clear",
        "MybatisSemanticCacheStore#remove",
        // 敏感词命中日志：旁路观测，失败不能反过来打断正在进行的过滤
        "MybatisSensitiveWordHitLogStore#save",
        // 主体配额命中记录：只在触顶那一刻写一条，供运营看「谁在刷」，不是限流判定依据
        "MybatisSubjectQuotaHitStore#record",
        // 会话级清理：本就是尽力而为，残留行由会话过期兜底，抛出反而会打断正常的结束流程
        "MybatisDialogStageStore#remove",
        "MybatisSlotFillingStore#delete",
        "MybatisLongTermMemoryStore#clear");

    @Test
    @DisplayName("写方法失败必须向上传播；旁路写入才可吞，且须在允许清单里写明理由")
    void writeFailuresMustPropagateUnlessSideChannel() throws IOException {
        List<String> offenders = new ArrayList<>();
        int checked = 0;

        for (Path file : storeSources()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String className = file.getFileName().toString().replace(".java", "");
            for (MethodBody method : methods(source)) {
                if (!WRITE_METHOD.matcher(method.name).find()) {
                    continue;
                }
                int catchAt = method.body.indexOf("catch (Exception");
                if (catchAt < 0) {
                    continue;
                }
                checked++;
                boolean propagates = method.body.indexOf("throw ", catchAt) > 0;
                String id = className + "#" + method.name;
                if (!propagates && !SIDE_CHANNEL_WRITES.contains(id)) {
                    offenders.add(id + " 吞掉了写失败却不在旁路清单里"
                        + "——写失败被吞掉就是数据没了却没有任何信号");
                }
            }
        }

        if (checked == 0) {
            fail("未扫描到任何带 catch 的 Store 写方法，本测试的解析逻辑可能已失效");
        }
        if (!offenders.isEmpty()) {
            fail("Store 写失败处置不合约定，共 " + offenders.size() + " 处：\n  - "
                + String.join("\n  - ", offenders)
                + "\n\n要么让它抛（默认选择），要么加进 SIDE_CHANNEL_WRITES 并写明为什么它属于旁路。"
                + "\n写不出理由，就说明它该抛。");
        }
    }

    @Test
    @DisplayName("Store 的错误码是可检索的「域-动作-FAIL」大写串")
    void errorCodesMustBeSearchable() throws IOException {
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (Path file : storeSources()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            for (String line : source.split("\n")) {
                if (!line.contains("log.error(")) {
                    continue;
                }
                Matcher m = CODE_LITERAL.matcher(line);
                while (m.find()) {
                    String candidate = m.group(1);
                    // 只看形似错误码的（含连字符），普通文案参数跳过
                    if (!candidate.contains("-")) {
                        continue;
                    }
                    checked++;
                    if (!ERROR_CODE.matcher(candidate).matches()) {
                        offenders.add(file.getFileName() + "：" + candidate);
                    }
                }
            }
        }
        if (checked == 0) {
            fail("未扫描到任何 Store 错误码，本测试的解析逻辑可能已失效");
        }
        if (!offenders.isEmpty()) {
            fail("以下错误码不是可检索形态（应形如 TICKET-STORE-SAVE-FAIL），共 "
                + offenders.size() + " 处：\n  - " + String.join("\n  - ", offenders));
        }
    }

    private record MethodBody(String name, String body) {
    }

    /** 粗切方法体：到下一个方法头或文件尾为止，够用来判断 catch 之后有没有 throw。 */
    private static List<MethodBody> methods(String source) {
        List<MethodBody> out = new ArrayList<>();
        Matcher m = METHOD_HEAD.matcher(source);
        List<int[]> heads = new ArrayList<>();
        while (m.find()) {
            heads.add(new int[]{m.start(), m.end()});
            out.add(new MethodBody(m.group(1), ""));
        }
        List<MethodBody> result = new ArrayList<>();
        for (int i = 0; i < heads.size(); i++) {
            int bodyStart = heads.get(i)[1];
            int bodyEnd = (i + 1 < heads.size()) ? heads.get(i + 1)[0] : source.length();
            result.add(new MethodBody(out.get(i).name(), source.substring(bodyStart, bodyEnd)));
        }
        return result;
    }

    private static List<Path> storeSources() throws IOException {
        Path root = resolveModulePath(STARTER_SOURCE_ROOT);
        if (!Files.exists(root)) {
            fail("找不到 starter 源码目录：" + root.toAbsolutePath());
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().matches("Mybatis\\w+Store\\.java"))
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
