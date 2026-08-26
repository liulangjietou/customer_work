package com.richard.fyoung.customeradmin.common.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>跨库门面写入姿态门禁</b>：每个 {@link CustomerWorkFacade} 都必须显式声明只读与否，
 * 且"自称只读"与"真的只读"不得分家。
 *
 * <p><b>为什么需要这个测试</b>：{@code faa1c7bf}（收敛 starter 散落装配）把 9 个能力域的建池代码
 * 收进 {@code CustomerWorkFacade} 时，{@code maxPoolSize} 被逐个搬了过来，而当时只有 callstats
 * 一处用到的 {@code .readOnly(true)} 没有——它的 javadoc 却原样保留了"连接池<b>只读</b>：
 * 这里只查客服端的调用日志，写入是 8080 那边的事"。于是从那天起：</p>
 * <ul>
 *   <li>后台"智能体调用统计"页的删除按钮，能物理删除客服端<b>运行库</b>的 {@code cw_agent_call_log}；</li>
 *   <li>五处注释仍在描述一个已不存在的保护，{@code ContentGuardGatewayProvider} 甚至在拿自己
 *       跟"callstats 的只读池"做对比——那个区别当时已经没了。</li>
 * </ul>
 *
 * <p>这类缺陷编译不报错、单测不变红：每一处单独看都自洽，只有把"注释说的"和"代码做的"放在一起
 * 才看得出来。常规单测做不到这件事，所以本测试<b>直接扫描源码</b>。</p>
 *
 * <p><b>为什么要求"显式声明"而不只是"自称只读的必须为 true"</b>：危险的默认值是 {@code false}
 * ——忘了想这件事的人会得到一个可写的池，而错误的方向恰好是数据被改。强制每个调用点写出
 * {@code .readOnly(true)} 或 {@code .readOnly(false)}，是把"忘了决策"变成"编译期就得决策"。
 * 新增第 13 个门面而不声明，这里会红。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class CustomerWorkFacadeReadOnlyAlignmentTest {

    private static final String ADMIN_SOURCE_ROOT = "customer-admin-server/src/main/java";

    /** 门面装配入口；出现它就意味着这个文件建了一个跨库连接池。 */
    private static final Pattern BUILDER_CALL = Pattern.compile("CustomerWorkFacade\\.builder\\(");

    /** 显式的写入姿态声明。 */
    private static final Pattern READ_ONLY_DECL = Pattern.compile("\\.readOnly\\((true|false)\\)");

    /**
     * javadoc 里"这是个只读门面"的几种说法。
     *
     * <p>刻意<b>不</b>匹配裸的"只读"二字：{@code EvalGatewayProvider} 写的是"默认只读当前有效租户"
     * （说的是租户范围不是连接池），{@code ContentGuardGatewayProvider} 写的是"与 callstats 的只读池不同"
     * （说的是别人）。用整句短语而不是单个词，才不会把这两处误判成只读门面。
     * 新增别的说法时加进来，加漏了的后果是漏掉一次校验而不是误报。</p>
     */
    private static final List<String> READ_ONLY_CLAIMS = List.of(
        "只读门面", "连接池<b>只读</b>", "只读接入", "只读数据源");

    @Test
    @DisplayName("每个跨库门面都显式声明 readOnly，且自称只读的必须真的只读")
    void facadeReadOnlyPostureMustBeExplicitAndTruthful() throws IOException {
        List<Path> providers = findFacadeProviders();
        if (providers.isEmpty()) {
            fail("未扫描到任何 CustomerWorkFacade.builder( 调用点，测试的定位逻辑可能已失效");
        }

        List<String> problems = new ArrayList<>();
        for (Path file : providers) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String name = file.getFileName().toString();

            Matcher declared = READ_ONLY_DECL.matcher(source);
            if (!declared.find()) {
                problems.add(name + "：建了跨库连接池却没有显式声明 .readOnly(true/false)。"
                    + "这个决定不能靠默认值——默认是可写，而忘了想的后果是客服端运行库被误写。");
                continue;
            }
            boolean readOnly = "true".equals(declared.group(1));
            boolean claimsReadOnly = READ_ONLY_CLAIMS.stream().anyMatch(source::contains);

            if (claimsReadOnly && !readOnly) {
                problems.add(name + "：javadoc 自称只读门面，代码却是 .readOnly(false)。"
                    + "注释挡不住任何一次误写——要么把它改成 .readOnly(true)，要么删掉那句不成立的描述。");
            }
            if (!claimsReadOnly && readOnly) {
                problems.add(name + "：代码是 .readOnly(true) 但 javadoc 没说明它是只读门面。"
                    + "下一个人会以为这里能写，请在类注释里写清楚（用词见 READ_ONLY_CLAIMS）。");
            }
        }

        if (!problems.isEmpty()) {
            fail("跨库门面写入姿态不一致，共 " + problems.size() + " 处：\n  - "
                + String.join("\n  - ", problems));
        }
    }

    /** 扫出所有调用 {@code CustomerWorkFacade.builder(} 的 admin 源文件（门面自身除外）。 */
    private static List<Path> findFacadeProviders() throws IOException {
        Path root = resolveModulePath(ADMIN_SOURCE_ROOT);
        if (!Files.exists(root)) {
            fail("找不到 admin 源码目录：" + root.toAbsolutePath());
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .filter(p -> !p.getFileName().toString().equals("CustomerWorkFacade.java"))
                .filter(CustomerWorkFacadeReadOnlyAlignmentTest::callsFacadeBuilder)
                .sorted()
                .toList();
        }
    }

    private static boolean callsFacadeBuilder(Path file) {
        try {
            return BUILDER_CALL.matcher(Files.readString(file, StandardCharsets.UTF_8)).find();
        } catch (IOException e) {
            throw new IllegalStateException("读取源文件失败：" + file, e);
        }
    }

    /** 兼容两种工作目录：仓库根（多模块构建）与模块目录（IDE 单模块跑测试）。 */
    private static Path resolveModulePath(String moduleRelative) {
        Path fromRepoRoot = Paths.get(moduleRelative);
        return Files.exists(fromRepoRoot) ? fromRepoRoot : Paths.get("..").resolve(moduleRelative);
    }
}
