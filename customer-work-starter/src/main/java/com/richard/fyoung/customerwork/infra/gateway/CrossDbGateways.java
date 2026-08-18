package com.richard.fyoung.customerwork.infra.gateway;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.plugin.Interceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@link CrossDbGateway} 静态工厂：把"另开一个库读写"这件事的固定套路收成一份。
 *
 * <p>套路本身有四个必须成套出现的动作，散着写必然漏掉其中之一：<br>
 * 1. Hikari 池 {@code initializationFailTimeout=-1}——构造连接池不因库不可达而抛，宿主启动期绝不被外库拖死；<br>
 * 2. 随后<b>显式</b>取一条连接做探测——失败信息比池内部报错明确，且能立刻转成业务提示；<br>
 * 3. 探测失败关掉半开的池再抛，不留泄漏；<br>
 * 4. 现场构建<b>专用</b> {@link SqlSessionFactory} 且不注册成 Spring Bean（详见 {@link CrossDbGateway}）。</p>
 *
 * <p>惰性 + 缓存 + "失败不缓存可重试"的语义见 {@link #lazy}，由 {@link CrossDbGatewayProvider} 承载；
 * 宿主已有连接池、只需要另一套独立 Mapper 环境（第 4 条）时用 {@link #attach}，它不建池也不关池。</p>
 * @author owlzhangfq@gmail.com
 */
public final class CrossDbGateways {

    private static final Logger log = LoggerFactory.getLogger(CrossDbGateways.class);

    /** 借用宿主数据源时的 URL 占位（连接参数在宿主那边，这里只用于日志/异常展示）。 */
    private static final String HOST_MANAGED_URL = "(host-managed)";

    private CrossDbGateways() {
    }

    /**
     * 立即构建一条跨库门面：建池 → 探测连通性 → 装配专用 MyBatis 环境。
     *
     * @param settings          连接参数
     * @param mapperClasses     无 XML、只用 {@code BaseMapper} CRUD 的 Mapper 接口（有 XML 的靠 namespace 自动绑定，
     *                          不要重复登记，否则同名语句会冲突）
     * @param mapperXmlLocations Mapper XML 的 classpath 模式（jar 内资源必须用 {@code classpath*:} 才命中）
     * @throws CrossDbUnavailableException 库不可达
     * @throws IllegalStateException       MyBatis 环境装配失败（XML/Mapper 写错这类代码问题）
     */
    public static CrossDbGateway create(CrossDbConnectionSettings settings,
                                        List<Class<?>> mapperClasses,
                                        List<String> mapperXmlLocations) {
        return create(settings, mapperClasses, mapperXmlLocations, List.of());
    }

    /** 构建携带宿主安全插件链的跨库门面。 */
    public static CrossDbGateway create(CrossDbConnectionSettings settings,
                                        List<Class<?>> mapperClasses,
                                        List<String> mapperXmlLocations,
                                        List<Interceptor> plugins) {
        HikariDataSource dataSource = buildAndProbeDataSource(settings);
        try {
            SqlSessionFactory factory = buildSqlSessionFactory(
                dataSource, mapperClasses, mapperXmlLocations, plugins);
            CrossDbGateway gateway = new CrossDbGateway(settings.poolName(), settings.jdbcUrl(),
                dataSource, dataSource, factory);
            log.info("cross-db gateway ready, pool={}, url={}", settings.poolName(), settings.jdbcUrl());
            return gateway;
        } catch (Exception e) {
            // 装配失败要关掉已建好的池，否则每次重试都漏一个池
            dataSource.close();
            throw new IllegalStateException("failed to build cross-db gateway, pool=" + settings.poolName(), e);
        }
    }

    /**
     * 在宿主已有的数据源上装配一套独立 MyBatis 环境：不建池、不探测、{@code close()} 也不关池（池归宿主管）。
     *
     * <p>用于"同一个库但需要另一套 Mapper 环境"的场景——目的和跨库一致：这套 Mapper 与
     * {@link SqlSessionFactory} 不能进容器（详见 {@link CrossDbGateway}），但连接池已经有了，不该再开一个。</p>
     *
     * @param dataSource         宿主数据源（生命周期由宿主容器负责）
     * @param name               标识，只用于日志与异常定位
     * @throws IllegalStateException MyBatis 环境装配失败
     */
    public static CrossDbGateway attach(DataSource dataSource, String name,
                                        List<Class<?>> mapperClasses,
                                        List<String> mapperXmlLocations) {
        return attach(dataSource, name, mapperClasses, mapperXmlLocations, List.of());
    }

    /** 在宿主数据源上装配携带安全插件的独立 Mapper 环境。 */
    public static CrossDbGateway attach(DataSource dataSource, String name,
                                        List<Class<?>> mapperClasses,
                                        List<String> mapperXmlLocations,
                                        List<Interceptor> plugins) {
        try {
            SqlSessionFactory factory = buildSqlSessionFactory(
                dataSource, mapperClasses, mapperXmlLocations, plugins);
            log.info("cross-db gateway attached to host datasource, name={}", name);
            return new CrossDbGateway(name, HOST_MANAGED_URL, dataSource, null, factory);
        } catch (Exception e) {
            throw new IllegalStateException("failed to build cross-db gateway, name=" + name, e);
        }
    }

    /**
     * 惰性跨库门面：首次 {@code get()} 才建池、探测并装配业务门面，构建成功后缓存复用、失败不缓存（下次重试）。
     *
     * <p>连接参数用 {@link Supplier} 而非固定值：配置对象由宿主容器持有，取值时机跟着首次访问走，
     * 不在持有者构造期就把 URL 定死。</p>
     *
     * @param settingsSupplier   连接参数供给（每次尝试构建时取一次）
     * @param mapperClasses      见 {@link #create}
     * @param mapperXmlLocations 见 {@link #create}
     * @param assembler          把跨库环境装配成业务门面（通常是取若干 Mapper 打包成一个 record）
     * @param <T>                业务门面类型
     */
    public static <T> CrossDbGatewayProvider<T> lazy(Supplier<CrossDbConnectionSettings> settingsSupplier,
                                                     List<Class<?>> mapperClasses,
                                                     List<String> mapperXmlLocations,
                                                     Function<CrossDbGateway, T> assembler) {
        return new CrossDbGatewayProvider<>(settingsSupplier, mapperClasses, mapperXmlLocations, assembler);
    }

    /** 惰性门面的安全插件重载；插件在首次建连时创建。 */
    public static <T> CrossDbGatewayProvider<T> lazy(Supplier<CrossDbConnectionSettings> settingsSupplier,
                                                     List<Class<?>> mapperClasses,
                                                     List<String> mapperXmlLocations,
                                                     Supplier<List<Interceptor>> pluginsSupplier,
                                                     Function<CrossDbGateway, T> assembler) {
        return new CrossDbGatewayProvider<>(settingsSupplier, mapperClasses, mapperXmlLocations,
            pluginsSupplier, assembler);
    }

    /**
     * 连通性探测：取一条连接随即归还。整个跨库链路的防御式编程只此一处，别的地方拿到门面就直接用。
     *
     * @throws CrossDbUnavailableException 库不可达
     */
    static void probeDataSource(DataSource dataSource, String poolName, String jdbcUrl) {
        try (Connection ignored = dataSource.getConnection()) {
            log.info("cross-db gateway probe ok, pool={}", poolName);
        } catch (Exception e) {
            throw new CrossDbUnavailableException(poolName, jdbcUrl, e);
        }
    }

    /** 建池并探测；任何失败都关闭半开的池再抛，绝不把不可用的池留给调用方。 */
    private static HikariDataSource buildAndProbeDataSource(CrossDbConnectionSettings settings) {
        HikariDataSource dataSource = null;
        try {
            HikariConfig config = new HikariConfig();
            config.setPoolName(settings.poolName());
            config.setDriverClassName(settings.driverClassName());
            config.setJdbcUrl(settings.jdbcUrl());
            config.setUsername(settings.username());
            config.setPassword(settings.password());
            config.setMaximumPoolSize(settings.maximumPoolSize());
            config.setMinimumIdle(settings.minimumIdle());
            config.setConnectionTimeout(settings.connectionTimeoutMs());
            config.setReadOnly(settings.readOnly());
            // 惰性：构造连接池本身不因不可达而抛（探测放在下方显式 getConnection，失败信息更明确可控）
            config.setInitializationFailTimeout(-1L);
            dataSource = new HikariDataSource(config);
            probeDataSource(dataSource, settings.poolName(), settings.jdbcUrl());
            return dataSource;
        } catch (Exception e) {
            if (dataSource != null) {
                dataSource.close();
            }
            if (e instanceof CrossDbUnavailableException unavailable) {
                throw unavailable;
            }
            throw new CrossDbUnavailableException(settings.poolName(), settings.jdbcUrl(), e);
        }
    }

    private static SqlSessionFactory buildSqlSessionFactory(DataSource dataSource,
                                                            List<Class<?>> mapperClasses,
                                                            List<String> mapperXmlLocations,
                                                            List<Interceptor> plugins) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        // 列名下划线、DO 字段驼峰，必须开启映射（created_at_ms -> createdAtMs）
        configuration.setMapUnderscoreToCamelCase(true);
        if (mapperClasses != null) {
            // 无 XML 的 Mapper 直接注册接口，MP 据此注入 BaseMapper 的 CRUD
            for (Class<?> mapperClass : mapperClasses) {
                configuration.addMapper(mapperClass);
            }
        }

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        if (plugins != null && !plugins.isEmpty()) {
            factoryBean.setPlugins(plugins.toArray(new Interceptor[0]));
        }
        // 基建工厂不打 mybatis-plus 启动横幅：宿主的主 SqlSessionFactory 已打过一次，这里每建一个
        // 跨库网关就再打一个（admin 启动曾连打三个）。defaults() 与不设置时框架内部的兜底同源，
        // 除 banner 开关外行为零变化。
        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        globalConfig.setBanner(false);
        factoryBean.setGlobalConfig(globalConfig);
        if (mapperXmlLocations != null && !mapperXmlLocations.isEmpty()) {
            factoryBean.setMapperLocations(resolveMapperXml(mapperXmlLocations));
        }
        factoryBean.afterPropertiesSet();
        return factoryBean.getObject();
    }

    private static Resource[] resolveMapperXml(List<String> mapperXmlLocations) throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Resource> resources = new ArrayList<>();
        for (String location : mapperXmlLocations) {
            resources.addAll(Arrays.asList(resolver.getResources(location)));
        }
        return resources.toArray(new Resource[0]);
    }
}
