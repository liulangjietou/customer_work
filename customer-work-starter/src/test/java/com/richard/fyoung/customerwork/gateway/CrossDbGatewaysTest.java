package com.richard.fyoung.customerwork.gateway;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CrossDbGateways} 单测：覆盖跨库门面的四条关键语义——
 * Mapper 两种注册方式（接口 / XML namespace）都能查、库不可达抛 {@link CrossDbUnavailableException}、
 * 惰性构建与成功缓存、失败不缓存可重试、close 真正关池。
 *
 * <p>可达用例走 H2 内存库（单测允许），不可达用例指向必然关闭的端口，都不依赖外部环境，CI 可跑。</p>
 * @author owlzhangfq@gmail.com
 */
class CrossDbGatewaysTest {

    /** DB_CLOSE_DELAY=-1：连接全关后库仍在，供多个池先后连接同一份数据。 */
    private static final String H2_URL = "jdbc:h2:mem:cross_db_gateways_test;DB_CLOSE_DELAY=-1;MODE=MySQL";
    private static final String H2_DRIVER = "org.h2.Driver";
    private static final String H2_USER = "sa";
    private static final String H2_PASSWORD = "";

    private static final String XML_LOCATION = "classpath*:crossdbtest/*.xml";

    @BeforeAll
    static void initSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(H2_URL, H2_USER, H2_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists cross_db_test_item "
                + "(id bigint primary key, item_name varchar(64))");
            statement.execute("delete from cross_db_test_item");
            statement.execute("insert into cross_db_test_item (id, item_name) values (1, 'alpha')");
        }
    }

    @Test
    void create_shouldRegisterMapperInterface_andQuery() {
        try (CrossDbGateway gateway = CrossDbGateways.create(h2Settings("crossdb-iface-pool"),
            List.of(CrossDbTestAnnotationMapper.class), List.of())) {
            String name = gateway.getMapper(CrossDbTestAnnotationMapper.class).selectNameById(1L);
            assertEquals("alpha", name);
        }
    }

    @Test
    void create_shouldLoadMapperXml_andQuery() {
        try (CrossDbGateway gateway = CrossDbGateways.create(h2Settings("crossdb-xml-pool"),
            List.of(), List.of(XML_LOCATION))) {
            // 未做接口注册，能查到即证明 XML namespace 绑定完成了注册
            String name = gateway.getMapper(CrossDbTestXmlMapper.class).selectNameById(1L);
            assertEquals("alpha", name);
        }
    }

    @Test
    void probe_shouldPass_whenDbReachable() {
        try (CrossDbGateway gateway = CrossDbGateways.create(h2Settings("crossdb-probe-pool"),
            List.of(CrossDbTestAnnotationMapper.class), List.of())) {
            gateway.probe();
        }
    }

    @Test
    void close_shouldShutdownPool() {
        CrossDbGateway gateway = CrossDbGateways.create(h2Settings("crossdb-close-pool"),
            List.of(CrossDbTestAnnotationMapper.class), List.of());
        assertFalse(((HikariDataSource) gateway.dataSource()).isClosed());
        gateway.close();
        assertTrue(((HikariDataSource) gateway.dataSource()).isClosed());
    }

    @Test
    void attach_shouldUseHostDataSource_andQuery() {
        HikariDataSource hostDataSource = hostDataSource();
        try {
            CrossDbGateway gateway = CrossDbGateways.attach(hostDataSource, "crossdb-attach",
                List.of(CrossDbTestAnnotationMapper.class), List.of());
            assertEquals("alpha", gateway.getMapper(CrossDbTestAnnotationMapper.class).selectNameById(1L));

            // 借来的池归宿主管：close 必须是空操作，否则宿主的连接池会被门面顺手关掉
            gateway.close();
            assertFalse(hostDataSource.isClosed());
            assertEquals("alpha", gateway.getMapper(CrossDbTestAnnotationMapper.class).selectNameById(1L));
        } finally {
            hostDataSource.close();
        }
    }

    @Test
    void create_shouldThrowCrossDbUnavailable_whenDbUnreachable() {
        CrossDbUnavailableException e = assertThrows(CrossDbUnavailableException.class,
            () -> CrossDbGateways.create(unreachableSettings(), List.of(), List.of()));
        assertEquals("cross-db-unreachable-pool", e.getPoolName());
        assertFalse(e.rootMessage().isEmpty());
    }

    @Test
    void lazy_shouldNotBuild_untilFirstGet() {
        AtomicInteger settingsCalls = new AtomicInteger();
        CrossDbGatewayProvider<String> provider = CrossDbGateways.lazy(() -> {
            settingsCalls.incrementAndGet();
            return unreachableSettings();
        }, List.of(), List.of(), gateway -> "facade");

        // 构造阶段一次都不该碰库（宿主启动期不能被外库拖死）
        assertEquals(0, settingsCalls.get());
        assertFalse(provider.isInitialized());
    }

    @Test
    void lazy_shouldCacheFacade_afterSuccess() {
        AtomicInteger settingsCalls = new AtomicInteger();
        AtomicInteger assembleCalls = new AtomicInteger();
        CrossDbGatewayProvider<Object> provider = CrossDbGateways.lazy(() -> {
            settingsCalls.incrementAndGet();
            return h2Settings("crossdb-cache-pool");
        }, List.of(CrossDbTestAnnotationMapper.class), List.of(), gateway -> {
            assembleCalls.incrementAndGet();
            return new Object();
        });

        Object first = provider.get();
        Object second = provider.get();
        assertSame(first, second);
        assertEquals(1, settingsCalls.get());
        assertEquals(1, assembleCalls.get());
        assertTrue(provider.isInitialized());
        provider.close();
    }

    @Test
    void lazy_shouldNotCacheFailure_andRetryEachTime() {
        AtomicInteger settingsCalls = new AtomicInteger();
        CrossDbGatewayProvider<String> provider = CrossDbGateways.lazy(() -> {
            settingsCalls.incrementAndGet();
            return unreachableSettings();
        }, List.of(), List.of(), gateway -> "facade");

        assertThrows(CrossDbUnavailableException.class, provider::get);
        // 第二次仍重新尝试（失败不缓存，覆盖库稍后恢复的场景）
        assertThrows(CrossDbUnavailableException.class, provider::get);
        assertEquals(2, settingsCalls.get());
        assertFalse(provider.isInitialized());
    }

    @Test
    void lazy_close_shouldShutdownUnderlyingPool() {
        AtomicReference<CrossDbGateway> captured = new AtomicReference<>();
        CrossDbGatewayProvider<Object> provider = CrossDbGateways.lazy(
            () -> h2Settings("crossdb-lazy-close-pool"),
            List.of(CrossDbTestAnnotationMapper.class), List.of(), gateway -> {
                captured.set(gateway);
                return new Object();
            });

        provider.get();
        provider.close();
        assertTrue(((HikariDataSource) captured.get().dataSource()).isClosed());
    }

    @Test
    void lazy_close_shouldBeNoop_whenNeverInitialized() {
        CrossDbGatewayProvider<String> provider = CrossDbGateways.lazy(this::unreachableSettings,
            List.of(), List.of(), gateway -> "facade");
        // 没建过就不该为了关闭反而去连库
        provider.close();
        assertFalse(provider.isInitialized());
    }

    /** 模拟宿主容器自己管理的数据源（attach 场景）。 */
    private HikariDataSource hostDataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("crossdb-host-pool");
        dataSource.setDriverClassName(H2_DRIVER);
        dataSource.setJdbcUrl(H2_URL);
        dataSource.setUsername(H2_USER);
        dataSource.setPassword(H2_PASSWORD);
        dataSource.setMaximumPoolSize(2);
        return dataSource;
    }

    private CrossDbConnectionSettings h2Settings(String poolName) {
        return CrossDbConnectionSettings.builder(poolName, H2_URL)
            .credentials(H2_USER, H2_PASSWORD)
            .driverClassName(H2_DRIVER)
            .maximumPoolSize(2)
            .build();
    }

    private CrossDbConnectionSettings unreachableSettings() {
        // 65534 端口默认无监听，连接立即被拒（不会长时间超时）
        return CrossDbConnectionSettings.builder("cross-db-unreachable-pool",
                "jdbc:mysql://127.0.0.1:65534/nowhere")
            .credentials("root", "root")
            .connectionTimeoutMs(1000L)
            .build();
    }
}
