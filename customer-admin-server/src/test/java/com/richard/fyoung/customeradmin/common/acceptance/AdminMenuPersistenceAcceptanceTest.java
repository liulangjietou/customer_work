package com.richard.fyoung.customeradmin.common.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.richard.fyoung.customeradmin.common.mp.MyMetaObjectHandler;
import com.richard.fyoung.customeradmin.config.MybatisPlusConfig;
import com.richard.fyoung.customeradmin.datascope.DataScopeProperties;
import com.richard.fyoung.customeradmin.menu.service.MenuVersionHolder;
import com.richard.fyoung.customeradmin.system.menu.dto.MenuReorderRequest;
import com.richard.fyoung.customeradmin.system.menu.mapper.SysMenuChangeLogMapper;
import com.richard.fyoung.customeradmin.system.menu.service.MenuChangeLogService;
import com.richard.fyoung.customeradmin.system.permission.mapper.SysPermissionMapper;
import com.richard.fyoung.customeradmin.system.permission.service.PermissionService;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.infra.lock.DistributedLockExecutor;
import com.richard.fyoung.customerwork.infra.lock.RedissonDistributedLockExecutor;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** 菜单排序的真实事务与发布版本；Redis 锁键加测试独占前缀，不触碰正在使用的后台锁。 */
class AdminMenuPersistenceAcceptanceTest {
    private static final long FIRST = 9_910_001L;
    private static final long SECOND = 9_910_002L;
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "127.0.0.1");
    private static final String PORT = System.getenv().getOrDefault("MYSQL_PORT", "3306");
    private static final String USER = System.getenv().getOrDefault("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("ADMIN_MYSQL_PASSWORD", "root");
    private static final String LOCK_PREFIX = "acceptance:menu:" + UUID.randomUUID() + ":";
    private static String database;
    private static JdbcTemplate jdbc;
    private static PermissionService service;
    private static MenuVersionHolder versions;
    private static RedissonClient redis;

    @BeforeAll
    static void createOwnedResources() throws Exception {
        String candidate = "admin_menu_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + candidate);
            database = candidate;
        }
        var source = new DriverManagerDataSource(url(database), USER, PASSWORD);
        jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .placeholderReplacement(false).load().migrate();
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(SysPermissionMapper.class);
        configuration.addMapper(SysMenuChangeLogMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        var tenant = new AdminTenantProperties();
        tenant.setEnabled(true);
        factory.setPlugins(new MybatisPlusConfig().mybatisPlusInterceptor(tenant, new DataScopeProperties()));
        var sessionFactory = factory.getObject();
        GlobalConfigUtils.getGlobalConfig(sessionFactory.getConfiguration()).setMetaObjectHandler(new MyMetaObjectHandler());
        var session = new SqlSessionTemplate(sessionFactory);
        var redisConfig = new Config();
        redisConfig.setThreads(2).setNettyThreads(2);
        var server = redisConfig.useSingleServer().setAddress("redis://"
            + System.getenv().getOrDefault("ADMIN_REDIS_HOST", "127.0.0.1") + ":"
            + System.getenv().getOrDefault("ADMIN_REDIS_PORT", "6379"));
        server.setConnectionPoolSize(4).setConnectionMinimumIdleSize(1);
        String redisPassword = System.getenv().getOrDefault("ADMIN_REDIS_PASSWORD", "");
        if (!redisPassword.isBlank()) server.setPassword(redisPassword);
        redis = Redisson.create(redisConfig);
        var realLocks = new RedissonDistributedLockExecutor(redis);
        DistributedLockExecutor scopedLocks = new DistributedLockExecutor() {
            @Override
            public <T> T execute(String key, Duration wait, Duration lease, Supplier<T> action) {
                return realLocks.execute(LOCK_PREFIX + key, wait, lease, action);
            }
        };
        versions = new MenuVersionHolder();
        var target = new PermissionService(session.getMapper(SysPermissionMapper.class),
            new MenuChangeLogService(session.getMapper(SysMenuChangeLogMapper.class)), versions, scopedLocks, null);
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        var transaction = new TransactionInterceptor();
        transaction.setTransactionManager(new DataSourceTransactionManager(source));
        transaction.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(transaction);
        service = (PermissionService) proxy.getProxy();
        // 与生产的 @Lazy 自注入相同，reorder 转入真实事务代理后才执行数据库更新。
        ReflectionTestUtils.setField(target, "self", service);
    }

    @BeforeEach
    void seedOwnedMenuNodes() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_second_menu_update");
        jdbc.update("DELETE FROM sys_permission WHERE id IN (?,?)", FIRST, SECOND);
        jdbc.update("INSERT INTO sys_permission(id,parent_id,perm_name,perm_code,type,sort) "
            + "VALUES (?,0,'验收菜单一','acceptance-menu-one',1,1),(?,0,'验收菜单二','acceptance-menu-two',1,2)", FIRST, SECOND);
    }

    @AfterAll
    static void removeOwnedResources() throws Exception {
        if (redis != null) redis.shutdown();
        if (database == null) return;
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + database);
        }
    }

    @Test
    void reorderedRowsAreVisibleAndOnlyExplicitPublishChangesTheVersion() {
        long before = versions.current();
        service.reorder(reversedOrder());
        assertEquals(List.of(SECOND, FIRST), ownedOrder());
        assertEquals(before, versions.current());
        service.publish();
        assertEquals(before + 1, versions.current());
        assertFalse(redis.getLock(LOCK_PREFIX + "admin:menu:reorder:lock").isLocked());
    }

    @Test
    void secondRowFailureRollsBackTheWholeReorderAndReleasesTheLockForRetry() {
        jdbc.execute("CREATE TRIGGER reject_second_menu_update BEFORE UPDATE ON sys_permission FOR EACH ROW "
            + "BEGIN IF OLD.id=" + FIRST + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='acceptance menu failure'; END IF; END");
        long before = versions.current();
        assertThrows(RuntimeException.class, () -> service.reorder(reversedOrder()));
        assertEquals(List.of(FIRST, SECOND), ownedOrder());
        assertEquals(before, versions.current());
        assertFalse(redis.getLock(LOCK_PREFIX + "admin:menu:reorder:lock").isLocked());
        jdbc.execute("DROP TRIGGER reject_second_menu_update");
        service.reorder(reversedOrder());
        assertEquals(List.of(SECOND, FIRST), ownedOrder());
    }

    private static List<Long> ownedOrder() {
        return jdbc.queryForList("SELECT id FROM sys_permission WHERE id IN (?,?) ORDER BY sort", Long.class, FIRST, SECOND);
    }

    private static MenuReorderRequest reversedOrder() {
        return new MenuReorderRequest(List.of(new MenuReorderRequest.Item(SECOND, 0L, 1),
            new MenuReorderRequest.Item(FIRST, 0L, 2)));
    }

    private static String url(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai";
    }
}
