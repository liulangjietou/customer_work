package com.richard.fyoung.customerwork.infra.config;

import com.richard.fyoung.customerwork.core.constant.StoreModes;
import com.richard.fyoung.customerwork.data.attachment.AttachmentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>持久化激活登记表门禁</b>：每个 {@code storeMode} 的配置键都必须登记进
 * {@link PersistenceJdbcCondition}，且<b>默认即 jdbc 的必须进 JDBC_BY_DEFAULT_KEYS</b>。
 *
 * <p><b>为什么这张表非要机器盯</b>：{@link PersistenceJdbcCondition} 决定整个持久化环境
 * （独立 DataSource / SqlSessionFactory / 全部 Mapper）装不装配。它在 Bean 定义注册期执行，
 * 早于属性绑定，因此只能读原始 {@code Environment}——<b>看不见 properties 类里的 Java 默认值</b>。
 * 这是 Spring 的机制约束，改不掉；能改的是"必须登记"由谁负责。</p>
 *
 * <p>漏登或错登的后果是<b>静默错配</b>：Store 按 Java 默认值取了 jdbc，而持久化环境没激活、
 * Mapper 根本没进容器。它不会在启动时明确报"你漏配了"，只会在第一次真正读写时炸，
 * 或者更糟——被别的默认 jdbc 的键顺带激活而看起来一切正常，直到某个宿主把那个键关掉。</p>
 *
 * <p>本测试用反射从 {@link CustomerWorkProperties} 走一遍对象图，按字段路径推导配置键，
 * 再拿字段的<b>实际默认值</b>去反查这张表。新增一个 store-mode 而忘了登记，这里会红。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class PersistenceJdbcConditionRegistryTest {

    private static final String ROOT_PREFIX = "customer-work";

    /** 走对象图的深度上限，防御属性类之间出现环引用时无限递归。 */
    private static final int MAX_DEPTH = 4;

    /**
     * 不参与判定的键：语义不是"这个域要不要落库"。
     *
     * <p>{@code outbox.store-mode} 默认 {@code auto}——它跟随 {@code ticket.store-mode}，
     * 自身不表达落库意图，故按"缺省未启用"登记在 STORE_MODE_KEYS 是对的。</p>
     */
    private static final Set<String> NOT_A_JDBC_DEFAULT = Set.of(
        // 默认 auto——跟随 ticket.store-mode，自身不表达落库意图
        ROOT_PREFIX + ".outbox.store-mode",
        // 默认 jdbc，但 AttachmentConfig#attachmentStore 取不到 Mapper 时会主动降级内存并打日志。
        // 有优雅降级的域不该仅凭自身默认值强制拉起整个持久化环境——那等于要求宿主必须有 MySQL，
        // 纯内存形态就跑不起来（CustomerWorkPersistenceConfigTest#whenAllMemory_shouldNotWireAnyPersistenceBean
        // 正是守着这条）。新增这类例外时必须在此写明降级发生在哪个类。
        ROOT_PREFIX + ".attachment.store-mode");

    @Test
    @DisplayName("每个 storeMode 配置键都已登记，且默认 jdbc 的落在 JDBC_BY_DEFAULT_KEYS")
    void everyStoreModeKeyMustBeRegistered() throws Exception {
        Set<String> anyList = new HashSet<>(readKeys("STORE_MODE_KEYS"));
        Set<String> jdbcByDefault = new HashSet<>(readKeys("JDBC_BY_DEFAULT_KEYS"));
        anyList.addAll(jdbcByDefault);

        Map<String, String> discovered = new LinkedHashMap<>();
        collectStoreModes(new CustomerWorkProperties(), ROOT_PREFIX, 0, discovered);
        // 独立成一份 @ConfigurationProperties（admin 排除自动装配后单独复用），不挂在聚合根下
        collectStoreModes(new AttachmentProperties(), ROOT_PREFIX + ".attachment", 1, discovered);

        if (discovered.isEmpty()) {
            fail("未从属性对象图里发现任何 storeMode 字段，本测试的推导逻辑可能已失效");
        }

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : discovered.entrySet()) {
            String key = e.getKey();
            String defaultValue = e.getValue();
            if (!anyList.contains(key)) {
                problems.add(key + " 未登记进 PersistenceJdbcCondition（Java 默认值 "
                    + defaultValue + "）——Store 想落库时持久化环境不会激活");
                continue;
            }
            boolean defaultsToJdbc = StoreModes.isJdbc(defaultValue);
            if (defaultsToJdbc && !jdbcByDefault.contains(key) && !NOT_A_JDBC_DEFAULT.contains(key)) {
                problems.add(key + " 的 Java 默认值是 " + defaultValue
                    + "，必须登记进 JDBC_BY_DEFAULT_KEYS（当前只在 STORE_MODE_KEYS 里）"
                    + "——那份清单在配置缺省时按「未启用」判定，与默认值相反");
            }
            if (!defaultsToJdbc && jdbcByDefault.contains(key)) {
                problems.add(key + " 的 Java 默认值是 " + defaultValue
                    + "，却登记在 JDBC_BY_DEFAULT_KEYS——会让持久化环境无谓地默认装配");
            }
        }

        if (!problems.isEmpty()) {
            fail("持久化激活登记表与属性默认值不一致，共 " + problems.size() + " 处：\n  - "
                + String.join("\n  - ", problems)
                + "\n\nPersistenceJdbcCondition 在 Bean 定义注册期执行，看不见 Java 默认值，"
                + "两处判定一旦漂移就是静默错配：Store 取 jdbc、Mapper 却没进容器。");
        }
    }

    /** 递归收集对象图里的 {@code *storeMode} 字段：字段路径 -> 当前默认值。 */
    private static void collectStoreModes(Object bean, String prefix, int depth,
                                          Map<String, String> out) throws Exception {
        if (depth > MAX_DEPTH || bean == null) {
            return;
        }
        Deque<Field> nested = new ArrayDeque<>();
        for (Field f : bean.getClass().getDeclaredFields()) {
            if (f.isSynthetic() || java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            String name = f.getName();
            if (f.getType() == String.class && name.toLowerCase().endsWith("storemode")) {
                out.put(prefix + "." + kebab(name), (String) f.get(bean));
            } else if (f.getType().getName().startsWith("com.richard.fyoung.customerwork")) {
                nested.add(f);
            }
        }
        for (Field f : nested) {
            collectStoreModes(f.get(bean), prefix + "." + kebab(f.getName()), depth + 1, out);
        }
    }

    /** {@code hitLogStoreMode} -> {@code hit-log-store-mode}（Spring Boot 的宽松绑定规则）。 */
    private static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
    }

    private static List<String> readKeys(String fieldName) throws Exception {
        Field f = PersistenceJdbcCondition.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return List.of((String[]) f.get(null));
    }
}
