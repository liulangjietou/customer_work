package com.richard.fyoung.customeradmin.sqlconfig.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.config.AdminSqlConfigProperties;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlQueryResultVO;
import com.richard.fyoung.customeradmin.sqlconfig.entity.SqlDatasource;
import com.richard.fyoung.customeradmin.sqlconfig.entity.SqlDefine;
import com.richard.fyoung.customeradmin.sqlconfig.entity.SqlDefineParam;
import com.richard.fyoung.customeradmin.sqlconfig.entity.SqlFieldTransform;
import com.richard.fyoung.customeradmin.sqlconfig.mapper.SqlDatasourceMapper;
import com.richard.fyoung.customeradmin.sqlconfig.mapper.SqlDefineMapper;
import com.richard.fyoung.customeradmin.sqlconfig.mapper.SqlDefineParamMapper;
import com.richard.fyoung.customeradmin.sqlconfig.mapper.SqlFieldTransformMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SQL 执行引擎真库集成测试（本机 MySQL localhost:3306 root/root，不可达自动跳过，同仓库既有 JDBC 门控约定）。
 *
 * <p>在 customer_admin 库建临时表插数据，走完整链路 {@link SqlQueryService#execute}：命名参数绑定、
 * pageNum→offset 换算、count_sql 总数、列序保持（AS 别名）、VALUE_MAP 列转换。测试结束删表。</p>
 * @author owlzhangfq@gmail.com
 */
class SqlQueryEngineIntegrationTest {

    private static final String JDBC_URL =
        "jdbc:mysql://localhost:3306/customer_admin?useUnicode=true&characterEncoding=utf8&useSSL=false"
            + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    private static final String USERNAME = "root";
    private static final String PASSWORD = "root";
    private static final String TABLE = "tmp_sql_cfg_it";

    private final AesGcmCryptoUtil cryptoUtil = new AesGcmCryptoUtil("0123456789abcdef");
    private SqlQueryService service;

    @BeforeAll
    static void initLambdaCache() {
        Configuration cfg = new Configuration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), SqlDefine.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), SqlDefineParam.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), SqlFieldTransform.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达（localhost:3306），跳过该集成测试");
        execute("DROP TABLE IF EXISTS " + TABLE,
            "CREATE TABLE " + TABLE + " (id BIGINT PRIMARY KEY, name VARCHAR(32), status INT)",
            "INSERT INTO " + TABLE + " VALUES (1,'a',1),(2,'b',0),(3,'c',1)");

        SqlDatasource datasource = new SqlDatasource();
        datasource.setId(1L);
        datasource.setName("it-db");
        datasource.setJdbcUrl(JDBC_URL);
        datasource.setUsername(USERNAME);
        datasource.setPassword(cryptoUtil.encrypt(PASSWORD));
        datasource.setEnabled(1);
        SqlDatasourceMapper datasourceMapper = mock(SqlDatasourceMapper.class);
        when(datasourceMapper.selectById(1L)).thenReturn(datasource);

        SqlDefine define = new SqlDefine();
        define.setId(1L);
        define.setDefineKey("it_query");
        define.setDatasourceId(1L);
        define.setEnabled(1);
        define.setQuerySql("SELECT id, name AS user_name, status FROM " + TABLE
            + " WHERE id >= :minId ORDER BY id LIMIT :sizeParam OFFSET :offsetParam");
        define.setCountSql("SELECT count(*) FROM " + TABLE + " WHERE id >= :minId");
        SqlDefineMapper defineMapper = mock(SqlDefineMapper.class);
        when(defineMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(define);

        SqlDefineParamMapper paramMapper = mock(SqlDefineParamMapper.class);
        when(paramMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            param("minId", "INTEGER", true, false, false, 1),
            param("offsetParam", "INTEGER", false, true, false, 2),
            param("sizeParam", "INTEGER", false, false, true, 3)));

        SqlFieldTransform statusTransform = new SqlFieldTransform();
        statusTransform.setFieldName("status");
        statusTransform.setTransformType("VALUE_MAP");
        statusTransform.setTransformConfig("{\"1\":\"启用\",\"0\":\"禁用\"}");
        SqlFieldTransformMapper transformMapper = mock(SqlFieldTransformMapper.class);
        when(transformMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(statusTransform));

        AdminSqlConfigProperties properties = new AdminSqlConfigProperties();
        SqlDatasourceConnectionManager connectionManager = new SqlDatasourceConnectionManager(datasourceMapper, cryptoUtil);
        service = new SqlQueryService(defineMapper, paramMapper, transformMapper, connectionManager,
            new FieldTransformer(), properties);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (reachable()) {
            execute("DROP TABLE IF EXISTS " + TABLE);
        }
    }

    @Test
    void execute_shouldBindParams_paginate_count_andTransform() {
        // page 1, size 2, minId=1 → 返回 id 1、2；总数 3
        SqlQueryResultVO result = service.execute("it_query",
            Map.of("minId", "1", "offsetParam", "1", "sizeParam", "2"));

        assertEquals(List.of("id", "user_name", "status"), result.getColumns());
        assertEquals(2, result.getRows().size());
        assertEquals(3L, result.getTotal());
        assertEquals("启用", result.getRows().get(0).get("status")); // id=1 status=1 → 启用
        assertEquals("禁用", result.getRows().get(1).get("status")); // id=2 status=0 → 禁用
    }

    private SqlDefineParam param(String name, String type, boolean required, boolean isPageNum, boolean isPageSize, int sort) {
        SqlDefineParam p = new SqlDefineParam();
        p.setParamName(name);
        p.setParamType(type);
        p.setRequired(required ? 1 : 0);
        p.setIsPageNum(isPageNum ? 1 : 0);
        p.setIsPageSize(isPageSize ? 1 : 0);
        p.setSort(sort);
        return p;
    }

    private void execute(String... sqls) throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            for (String sql : sqls) {
                statement.execute(sql);
            }
        }
    }

    private static boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 3306), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
