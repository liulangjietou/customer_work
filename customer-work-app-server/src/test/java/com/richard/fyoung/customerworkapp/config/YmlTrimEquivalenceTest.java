package com.richard.fyoung.customerworkapp.config;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * yml 瘦身<b>等价性</b>验证：精简后的 application.yml 绑定出的配置对象，
 * 必须与精简前那份逐字段相同。
 *
 * <p><b>为什么需要这个测试</b>：application.yml 里曾有 181 项配置的值与
 * {@code @ConfigurationProperties} 里的 Java 默认值完全相同——写了等于没写，
 * 却让"默认值"有了两个真相来源。改 Java 默认值时没人会想起 yml 里也写了一份，
 * 而实际生效的是 yml；瘦身前已经出现过一处分歧（{@code security.rate-limit.rule-enabled}
 * yml=true / Java=false）。</p>
 *
 * <p>删冗余是安全的<b>前提是真的等价</b>。这个测试把"我认为等价"变成机器可验证的事实：
 * 用 Spring 自己的 {@link Binder} 分别绑定瘦身前后的两份 yml，比较绑定结果。
 * 只要有任何一项被误删（或删错了一项其实是覆盖的），这里立刻红。</p>
 *
 * <p>基线文件 {@code application-ymlbaseline.yml} 是瘦身前那份的副本，只服务于本测试。
 * 后续如需<b>有意</b>修改默认行为，应同步更新行为来源与基线：通用默认行为改 Java 默认值；
 * app 的生产安全覆盖则保留显式 yml 配置。不要为了让测试通过而删掉测试或降级生产覆盖。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class YmlTrimEquivalenceTest {

    private static final YamlPropertySourceLoader LOADER = new YamlPropertySourceLoader();

    /** 被验证的主配置（相对模块根目录）。 */
    private static final String MAIN_YML = "src/main/resources/application.yml";
    /** 瘦身前的副本，只服务于本测试。 */
    private static final String BASELINE_YML = "src/test/resources/application-ymlbaseline.yml";

    /**
     * 把一份 yml 绑定成 CustomerWorkProperties（未在 yml 中出现的项自然取 Java 默认值）。
     *
     * <p>刻意走文件系统而非 classpath：测试类路径下有一份精简的
     * {@code src/test/resources/application.yml} 会遮蔽主资源那份，
     * 用 classpath 读到的将不是被验证的那个文件。</p>
     */
    private CustomerWorkProperties bind(String path) throws IOException {
        Resource resource = new FileSystemResource(path);
        assertTrue(resource.exists(), "找不到 " + path + "（工作目录应为模块根）");
        List<PropertySource<?>> sources = LOADER.load(path, resource);
        StandardEnvironment env = new StandardEnvironment();
        // 后加的优先级低，这里只有一份，顺序无关
        sources.forEach(s -> env.getPropertySources().addLast(s));
        return Binder.get(env)
            .bind("customer-work", CustomerWorkProperties.class)
            .orElseGet(CustomerWorkProperties::new);
    }

    @Test
    @DisplayName("瘦身后的 yml 与瘦身前绑定出完全相同的配置对象")
    void trimmedYmlBindsIdentically() throws IOException {
        CustomerWorkProperties before = bind(BASELINE_YML);
        CustomerWorkProperties after = bind(MAIN_YML);

        // CustomerWorkProperties 是 @Data，equals 递归比较全部域对象
        assertEquals(before, after,
            "瘦身后的 yml 绑定结果与瘦身前不一致 —— 说明删掉的项里有一项并非"
                + "「与 Java 默认值相同」，它实际改变了行为。用 toString 差异定位具体字段。");
    }

    @Test
    @DisplayName("调用事实日志保持 JDBC 持久化")
    void callLogFactsRemainPersisted() throws IOException {
        assertEquals("jdbc", bind(MAIN_YML).getCallLog().getStoreMode(),
            "调用日志支撑实验指标、SLO 与成本归因，app 配置不得退回进程内存");
    }

    /**
     * 顺带守住瘦身的成果：yml 不该重新长回去。
     *
     * <p>阈值取当前值加一点余量——新增配置项是正常的，成片贴回默认值不是。
     * 撞到这条时先问：新加的这些项，值是不是和 Java 默认值一样？</p>
     */
    @Test
    @DisplayName("application.yml 规模不回弹")
    void ymlStaysLean() throws IOException {
        Resource resource = new FileSystemResource(MAIN_YML);
        long lines = new String(resource.getInputStream().readAllBytes()).lines().count();
        assertTrue(lines <= 260,
            "application.yml 已涨到 " + lines + " 行（瘦身后 193 行）。"
                + "新增前请确认：这些项的值是否与 @ConfigurationProperties 的 Java 默认值相同？"
                + "相同就别写进 yml——默认值只该有一个真相来源。");
    }
}
