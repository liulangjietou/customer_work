package com.richard.fyoung.customeradmin.common.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.mp.MyMetaObjectHandler;
import com.richard.fyoung.customeradmin.config.MybatisPlusConfig;
import com.richard.fyoung.customeradmin.datascope.DataScope;
import com.richard.fyoung.customeradmin.datascope.DataScopeContext;
import com.richard.fyoung.customeradmin.datascope.DataScopeProperties;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlDefineParamSaveRequest;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlFieldTransformSaveRequest;
import com.richard.fyoung.customeradmin.sqlconfig.mapper.SqlDatasourceMapper;
import com.richard.fyoung.customeradmin.sqlconfig.mapper.SqlDefineMapper;
import com.richard.fyoung.customeradmin.sqlconfig.mapper.SqlDefineParamMapper;
import com.richard.fyoung.customeradmin.sqlconfig.mapper.SqlFieldTransformMapper;
import com.richard.fyoung.customeradmin.sqlconfig.service.SqlDefineService;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** SQL 子资源与复制的数据库边界；事务拦截器读取产品 Service 上的真实注解。 */
class AdminSqlChildrenAcceptanceTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "127.0.0.1");
    private static final String PORT = System.getenv().getOrDefault("MYSQL_PORT", "3306");
    private static final String USER = System.getenv().getOrDefault("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("ADMIN_MYSQL_PASSWORD", "root");
    private static final String TENANT = "sql-children-case";
    private static String database;
    private static JdbcTemplate jdbc;
    private static SqlDefineService service;

    @BeforeAll
    static void createOwnDatabase() throws Exception {
        String candidate = "admin_sql_children_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + candidate); database = candidate;
        }
        var source = new DriverManagerDataSource(url(database), USER, PASSWORD);
        jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .placeholderReplacement(false).load().migrate();
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(SqlDefineMapper.class, SqlDefineParamMapper.class, SqlFieldTransformMapper.class, SqlDatasourceMapper.class)) {
            configuration.addMapper(mapper);
        }
        var tenant = new AdminTenantProperties(); tenant.setEnabled(true);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
        factory.setPlugins(new MybatisPlusConfig().mybatisPlusInterceptor(tenant, new DataScopeProperties()));
        var sessionFactory = factory.getObject();
        GlobalConfigUtils.getGlobalConfig(sessionFactory.getConfiguration()).setMetaObjectHandler(new MyMetaObjectHandler());
        var sql = new SqlSessionTemplate(sessionFactory);
        var target = new SqlDefineService(sql.getMapper(SqlDefineMapper.class), sql.getMapper(SqlDefineParamMapper.class),
            sql.getMapper(SqlFieldTransformMapper.class), sql.getMapper(SqlDatasourceMapper.class));
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        var transactions = new TransactionInterceptor();
        transactions.setTransactionManager(new DataSourceTransactionManager(source));
        transactions.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(transactions);
        service = (SqlDefineService) proxy.getProxy();
    }

    @BeforeEach
    void seedOwnDatabase() {
        TenantContext.set(TENANT); DataScopeContext.set(DataScope.TENANT, 7L);
        jdbc.execute("DROP TRIGGER IF EXISTS reject_copy_transform");
        for (String table : List.of("sql_define_param", "sql_field_transform", "sql_define", "sql_datasource")) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO sql_datasource(id,tenant_id,name,jdbc_url,username,password) VALUES (900,?,'验收数据源','jdbc:mysql://fixture.invalid/database','fixture','unused-cipher')", TENANT);
        jdbc.update("INSERT INTO sql_define(id,tenant_id,define_key,datasource_id,sql_describe,query_sql) "
            + "VALUES (901,?,'own-report',900,'原报表','SELECT 1'),(902,?,'other-parent',900,'另一个父定义','SELECT 2'),"
            + "(903,'SQL-CHILDREN-CASE','foreign-report',900,'外租户报表','SELECT 3')", TENANT, TENANT);
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); DataScopeContext.clear(); }

    @AfterAll
    static void removeOwnDatabase() throws Exception {
        if (database == null) return;
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) { statement.execute("DROP DATABASE " + database); }
    }

    @Test
    void parameterAndTransformWritesRequireExactTenantAndOriginalParent() {
        seedChildren();
        long paramId = service.listParams(901L).get(0).getId();
        long transformId = service.listTransforms(901L).get(0).getId();
        service.updateParam(901L, paramId, parameter("已核对参数"));
        service.updateTransform(901L, transformId, new SqlFieldTransformSaveRequest("created_at", "DATE_FORMAT", "yyyy-MM-dd"));
        assertEquals("已核对参数", service.listParams(901L).get(0).getParamDesc());
        assertEquals("yyyy-MM-dd", service.listTransforms(901L).get(0).getTransformConfig());
        assertThrows(BizException.class, () -> service.updateParam(902L, paramId, parameter("错误父定义")));
        assertThrows(BizException.class, () -> service.deleteTransform(902L, transformId));
        assertThrows(BizException.class, () -> service.createParam(903L, parameter("外租户")));
        assertThrows(BizException.class, () -> service.listTransforms(903L));
        TenantContext.runWith("SQL-CHILDREN-CASE", () -> {
            assertThrows(BizException.class, () -> service.deleteParam(901L, paramId));
            assertThrows(BizException.class, () -> service.updateTransform(901L, transformId,
                new SqlFieldTransformSaveRequest("created_at", "DATE_FORMAT", "MM")));
        });
        service.deleteParam(901L, paramId); service.deleteTransform(901L, transformId);
        assertEquals(0, service.listParams(901L).size()); assertEquals(0, service.listTransforms(901L).size());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM sql_define WHERE deleted=0", Integer.class));
    }

    @Test
    void copyPreservesBothChildrenAndCreatesOnlyAnOwnedNewDefinition() {
        seedChildren(); service.copy(901L);
        long copyId = jdbc.queryForObject("SELECT id FROM sql_define WHERE define_key='own-report-copy'", Long.class);
        assertEquals(TENANT, jdbc.queryForObject("SELECT tenant_id FROM sql_define WHERE id=?", String.class, copyId));
        assertEquals("order_id", service.listParams(copyId).get(0).getParamName());
        assertEquals("created_at", service.listTransforms(copyId).get(0).getFieldName());
        assertEquals(1, service.listParams(901L).size()); assertEquals(1, service.listTransforms(901L).size());
        assertThrows(BizException.class, () -> service.copy(903L));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM sql_define WHERE deleted=0", Integer.class));
    }

    @Test
    void copyChildWriteFailureRollsBackTheNewDefinitionAndCopiedParameters() {
        seedChildren();
        jdbc.execute("CREATE TRIGGER reject_copy_transform BEFORE INSERT ON sql_field_transform FOR EACH ROW "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='acceptance copy failure'");
        assertThrows(RuntimeException.class, () -> service.copy(901L));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM sql_define", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sql_define_param", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sql_field_transform", Integer.class));
    }

    private static void seedChildren() {
        service.createParam(901L, parameter("原参数"));
        service.createTransform(901L, new SqlFieldTransformSaveRequest("created_at", "DATE_FORMAT", "MM-dd"));
    }

    private static SqlDefineParamSaveRequest parameter(String description) {
        return new SqlDefineParamSaveRequest("order_id", description, "STRING", null, false, "", "", false, false, 0);
    }

    private static String url(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai";
    }
}
