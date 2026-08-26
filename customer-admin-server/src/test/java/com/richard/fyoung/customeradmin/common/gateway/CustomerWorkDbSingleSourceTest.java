package com.richard.fyoung.customeradmin.common.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>客服端库连接单一真相门禁</b>：{@link CustomerWorkDbConnection} 的实现只能有一个。
 *
 * <p><b>为什么需要这个测试</b>：同一个物理库（{@code agent_scope_customer_work}）此前被三个
 * {@code @ConfigurationProperties} 各配了一份——{@code admin.content-guard.*}、{@code admin.dict.*}、
 * {@code admin.agent-call-stats.app.*}，五个连接字段默认值逐字相同、{@code jdbcUrl()} 方法体一模一样。
 * 它们不是"三个数据源"，是同一件事被写了三遍。</p>
 *
 * <p>真正的代价不在多写两个类，而在<b>没人愿意在 yml 里把同一个库的连接抄三遍</b>：
 * 于是三份连接一个 {@code ${ENV}} 占位都没写，既不在部署手册的"生产必配"表里、
 * 也不在 {@code deploy/k8s} 的 ConfigMap 里。按当时的清单部署，admin 容器里 12 个跨库门面
 * 会全部去连 pod 内的 {@code localhost:3306}，11 个页面一起报"客服端库不可达"；
 * 运维最容易搜到 {@code ContentGuardProperties}，补上它之后敏感词页恢复、字典页依旧报错——
 * 因为那是另一个前缀，而这个名字既不在 yml 也不在手册里，只能靠读源码猜。</p>
 *
 * <p>{@link CustomerWorkDbConnection} 接口刻意保留：将来某个域确实要接读库副本时仍能自行实现。
 * 但那必须是一次<b>有意</b>的新增，而不是"复制一份属性类顺手就有了"。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class CustomerWorkDbSingleSourceTest {

    private static final String ADMIN_SOURCE_ROOT = "customer-admin-server/src/main/java";
    private static final String ADMIN_RESOURCE_ROOT = "customer-admin-server/src/main/resources";

    private static final Pattern IMPLEMENTS_CONNECTION =
        Pattern.compile("class\\s+\\w+\\s+implements\\s+[^{]*\\bCustomerWorkDbConnection\\b");

    /** 客服端库 JDBC URL 的拼装；同一段字符串出现在两个类里就是两个真相。 */
    private static final Pattern JDBC_URL_LITERAL = Pattern.compile("\"jdbc:mysql://\"\\s*\\+");

    @Test
    @DisplayName("CustomerWorkDbConnection 只有一个实现：连接参数不得再被复制一份")
    void connectionMustHaveExactlyOneImplementation() throws IOException {
        List<Path> implementations = adminSources()
            .filter(p -> IMPLEMENTS_CONNECTION.matcher(read(p)).find())
            .sorted()
            .toList();

        List<String> names = implementations.stream()
            .map(p -> p.getFileName().toString())
            .toList();

        assertEquals(List.of("CustomerWorkDbProperties.java"), names,
            "客服端库连接参数必须只有一份。多一份同值副本 = 多一处会漂移的真相，"
                + "且没人会在 yml 里把同一个库抄两遍——最后哪一份都不会被显式配置。"
                + "确实要接读库副本时，请在本测试里显式登记并说明理由。实际找到："
                + names);
    }

    @Test
    @DisplayName("客服端库 JDBC URL 只在一处拼装")
    void jdbcUrlMustBeAssembledInOnePlace() throws IOException {
        List<String> assemblers = adminSources()
            .filter(p -> JDBC_URL_LITERAL.matcher(read(p)).find())
            .map(p -> p.getFileName().toString())
            .sorted()
            .toList();

        assertEquals(List.of("CustomerWorkDbProperties.java"), assemblers,
            "jdbcUrl() 的拼装（含 characterEncoding / serverTimezone 等参数）只能有一份；"
                + "此前三份逐字相同的副本意味着改连接参数要记得改三处。实际找到：" + assemblers);
    }

    @Test
    @DisplayName("连接参数在 yml 里带 ${ENV} 占位：生产不能只能靠改镜像里的 yml")
    void connectionMustBeEnvOverridable() throws IOException {
        String yml = Files.readString(
            resolveModulePath(ADMIN_RESOURCE_ROOT).resolve("application.yml"), StandardCharsets.UTF_8);
        int idx = yml.indexOf("customer-work-db:");
        assertTrue(idx > 0, "application.yml 里应有 admin.customer-work-db 段");

        String section = yml.substring(idx, Math.min(yml.length(), idx + 800));
        for (String env : List.of("MYSQL_HOST", "MYSQL_PORT", "MYSQL_DATABASE",
            "MYSQL_USERNAME", "MYSQL_PASSWORD")) {
            assertTrue(section.contains("${" + env),
                env + " 必须以 ${" + env + ":...} 形式可被环境变量覆盖——"
                    + "连的就是 app-server 那个库，理应共用同一套变量名，"
                    + "否则这一项又会从部署清单里消失");
        }
    }

    private static Stream<Path> adminSources() throws IOException {
        Path root = resolveModulePath(ADMIN_SOURCE_ROOT);
        if (!Files.exists(root)) {
            fail("找不到 admin 源码目录：" + root.toAbsolutePath());
        }
        return Files.walk(root)
            .filter(Files::isRegularFile)
            .filter(p -> p.getFileName().toString().endsWith(".java"));
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取源文件失败：" + p, e);
        }
    }

    /** 兼容两种工作目录：仓库根（多模块构建）与模块目录（IDE 单模块跑测试）。 */
    private static Path resolveModulePath(String moduleRelative) {
        Path fromRepoRoot = Paths.get(moduleRelative);
        return Files.exists(fromRepoRoot) ? fromRepoRoot : Paths.get("..").resolve(moduleRelative);
    }
}
