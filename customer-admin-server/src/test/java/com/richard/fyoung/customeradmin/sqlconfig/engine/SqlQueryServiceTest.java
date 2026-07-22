package com.richard.fyoung.customeradmin.sqlconfig.engine;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminSqlConfigProperties;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlQueryMetaVO;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlQueryResultVO;
import com.richard.fyoung.customeradmin.sqlconfig.entity.SqlDefine;
import com.richard.fyoung.customeradmin.sqlconfig.entity.SqlDefineParam;
import com.richard.fyoung.customeradmin.sqlconfig.mapper.SqlDefineMapper;
import com.richard.fyoung.customeradmin.sqlconfig.mapper.SqlDefineParamMapper;
import com.richard.fyoung.customeradmin.sqlconfig.mapper.SqlFieldTransformMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SqlQueryService} 参数处理逻辑：类型转换、必填校验、pageNum→offset 换算、pageSize 钳制，
 * 以及 meta 元数据组装（默认值表达式解析、下拉解析）。
 * @author owlzhangfq@gmail.com
 */
class SqlQueryServiceTest {

    private SqlDefineMapper defineMapper;
    private SqlDefineParamMapper paramMapper;
    private SqlFieldTransformMapper transformMapper;
    private SqlDatasourceConnectionManager connectionManager;
    private SqlQueryService service;

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), SqlDefine.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), SqlDefineParam.class);
    }

    @BeforeEach
    void setUp() {
        defineMapper = mock(SqlDefineMapper.class);
        paramMapper = mock(SqlDefineParamMapper.class);
        transformMapper = mock(SqlFieldTransformMapper.class);
        connectionManager = mock(SqlDatasourceConnectionManager.class);
        FieldTransformer fieldTransformer = new FieldTransformer();
        AdminSqlConfigProperties properties = new AdminSqlConfigProperties();
        properties.setMaxRows(2000);
        properties.setQueryTimeoutSeconds(30);
        service = new SqlQueryService(defineMapper, paramMapper, transformMapper, connectionManager, fieldTransformer, properties);
    }

    @Test
    void computeOffset_shouldBeZeroBasedPageMath() {
        assertEquals(0L, SqlQueryService.computeOffset(1, 10));
        assertEquals(20L, SqlQueryService.computeOffset(3, 10));
        assertEquals(0L, SqlQueryService.computeOffset(0, 10));
    }

    @Test
    void bindParams_shouldConvertInteger_andRejectMissingRequired() {
        SqlDefineParam intParam = param("age", "INTEGER", false, null, false, false, 1);
        SqlDefineParam requiredParam = param("name", "STRING", true, null, false, false, 2);

        MapSqlParameterSource source = service.bindParams(List.of(intParam), Map.of("age", "25"), false);
        assertEquals(25L, source.getValue("age"));

        assertThrows(BizException.class,
            () -> service.bindParams(List.of(requiredParam), Map.of(), false));
    }

    @Test
    void bindParams_shouldTranslatePageNumToOffset_andClampPageSize() {
        SqlDefineParam pageNum = param("offsetParam", "INTEGER", false, null, true, false, 1);
        SqlDefineParam pageSize = param("sizeParam", "INTEGER", false, null, false, true, 2);

        MapSqlParameterSource source = service.bindParams(
            List.of(pageNum, pageSize), Map.of("offsetParam", "3", "sizeParam", "10"), false);

        assertEquals(20L, source.getValue("offsetParam")); // (3-1)*10
        assertEquals(10, source.getValue("sizeParam"));
    }

    @Test
    void bindParams_shouldClampPageSizeToMaxRows() {
        SqlDefineParam pageSize = param("sizeParam", "INTEGER", false, null, false, true, 1);

        MapSqlParameterSource source = service.bindParams(
            List.of(pageSize), Map.of("sizeParam", "999999"), false);

        assertEquals(2000, source.getValue("sizeParam"));
    }

    @Test
    void bindParams_export_shouldForceOffsetZeroAndMaxRows() {
        SqlDefineParam pageNum = param("offsetParam", "INTEGER", false, null, true, false, 1);
        SqlDefineParam pageSize = param("sizeParam", "INTEGER", false, null, false, true, 2);

        MapSqlParameterSource source = service.bindParams(
            List.of(pageNum, pageSize), Map.of("offsetParam", "5", "sizeParam", "10"), true);

        assertEquals(0L, source.getValue("offsetParam"));
        assertEquals(2000, source.getValue("sizeParam"));
    }

    @Test
    void bindParams_shouldApplyDefaultValue_whenAbsent() {
        SqlDefineParam withDefault = param("status", "STRING", false, "ACTIVE", false, false, 1);

        MapSqlParameterSource source = service.bindParams(List.of(withDefault), Map.of(), false);

        assertEquals("ACTIVE", source.getValue("status"));
    }

    @Test
    void meta_shouldResolveDefaultExpressionAndDropDown() {
        SqlDefine define = new SqlDefine();
        define.setId(1L);
        define.setDefineKey("user_list");
        define.setSqlDescribe("用户列表");
        define.setEnabled(1);
        define.setAutoLoad(1);
        define.setCountSql("SELECT count(*) FROM users");
        when(defineMapper.selectOne(any())).thenReturn(define);

        SqlDefineParam startTime = param("start", "DATETIME", false, "${now-7d}", false, false, 1);
        startTime.setDropDown(null);
        SqlDefineParam statusParam = param("status", "STRING", false, null, false, false, 2);
        statusParam.setDropDown("{\"1\":\"启用\",\"0\":\"禁用\"}");
        when(paramMapper.selectList(any())).thenReturn(List.of(startTime, statusParam));

        SqlQueryMetaVO meta = service.meta("user_list");

        assertEquals("user_list", meta.getDefineKey());
        assertTrue(meta.getAutoLoad());
        assertTrue(meta.getHasCountSql());
        assertEquals(2, meta.getParams().size());
        // ${now-7d} 已解析成实际时间串（19 位）
        assertEquals(19, meta.getParams().get(0).getDefaultValue().length());
        // 下拉 JSON 解析成 Map
        assertEquals("启用", meta.getParams().get(1).getDropDown().get("1"));
    }

    @Test
    void executeAdhoc_shouldRejectNonReadOnly_beforeTouchingDatasource() {
        // 非只读 SQL 必须在建连接前就被 SqlValidator 拦下，绝不触达数据库
        BizException ex = assertThrows(BizException.class,
            () -> service.executeAdhoc(1L, "DELETE FROM t_user"));
        assertEquals(ResultCode.SQL_NOT_READONLY, ex.getResultCode());
        verify(connectionManager, never()).getTemplate(any());
    }

    @Test
    void executeAdhoc_shouldRejectMultiStatement() {
        BizException ex = assertThrows(BizException.class,
            () -> service.executeAdhoc(1L, "SELECT 1; SELECT 2"));
        assertEquals(ResultCode.SQL_NOT_READONLY, ex.getResultCode());
        verify(connectionManager, never()).getTemplate(any());
    }

    @Test
    void executeAdhoc_shouldExecuteReadOnly_andSetTotalToRowCount() {
        NamedParameterJdbcTemplate npt = mock(NamedParameterJdbcTemplate.class);
        JdbcTemplate jt = mock(JdbcTemplate.class);
        when(connectionManager.getTemplate(1L)).thenReturn(npt);
        when(npt.getJdbcOperations()).thenReturn(jt);
        SqlQueryResultVO fake = new SqlQueryResultVO();
        fake.setColumns(List.of("id", "name"));
        // 用可变 Map（真实的 extract 产 LinkedHashMap），因执行后会 replaceAll 做时间列格式化
        java.util.Map<String, Object> r1 = new java.util.LinkedHashMap<>();
        r1.put("id", 1);
        r1.put("name", "a");
        java.util.Map<String, Object> r2 = new java.util.LinkedHashMap<>();
        r2.put("id", 2);
        r2.put("name", "b");
        fake.setRows(List.of(r1, r2));
        when(jt.query(eq("SELECT id, name FROM t_user"), any(ResultSetExtractor.class))).thenReturn(fake);

        SqlQueryResultVO result = service.executeAdhoc(1L, "SELECT id, name FROM t_user");

        assertEquals(2, result.getTotal(), "adhoc total 记实际返回行数");
        assertEquals(List.of("id", "name"), result.getColumns());
        verify(jt).setMaxRows(2000);
        verify(jt).setQueryTimeout(30);
    }

    @Test
    void execute_shouldFormatDateTime_withoutIsoT_afterTransforms() {
        SqlDefine define = new SqlDefine();
        define.setId(1L);
        define.setDefineKey("k");
        define.setEnabled(1);
        define.setDatasourceId(9L);
        define.setQuerySql("SELECT t FROM x");
        when(defineMapper.selectOne(any())).thenReturn(define);
        when(paramMapper.selectList(any())).thenReturn(List.of());
        when(transformMapper.selectList(any())).thenReturn(List.of()); // 无列转换器

        NamedParameterJdbcTemplate npt = mock(NamedParameterJdbcTemplate.class);
        JdbcTemplate jt = mock(JdbcTemplate.class);
        when(connectionManager.getTemplate(9L)).thenReturn(npt);
        when(npt.getJdbcOperations()).thenReturn(jt);
        SqlQueryResultVO fake = new SqlQueryResultVO();
        fake.setColumns(List.of("t"));
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("t", java.time.LocalDateTime.of(2026, 7, 20, 10, 0, 5));
        fake.setRows(new java.util.ArrayList<>(List.of(row)));
        when(npt.query(anyString(), any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class),
            any(ResultSetExtractor.class))).thenReturn(fake);

        SqlQueryResultVO result = service.execute("k", Map.of());

        assertEquals("2026-07-20 10:00:05", result.getRows().get(0).get("t"));
    }

    @Test
    void executeAdhoc_shouldFormatDateTime_withoutIsoT() {
        NamedParameterJdbcTemplate npt = mock(NamedParameterJdbcTemplate.class);
        JdbcTemplate jt = mock(JdbcTemplate.class);
        when(connectionManager.getTemplate(1L)).thenReturn(npt);
        when(npt.getJdbcOperations()).thenReturn(jt);
        SqlQueryResultVO fake = new SqlQueryResultVO();
        fake.setColumns(List.of("t"));
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("t", java.time.LocalDateTime.of(2026, 7, 20, 10, 0, 5));
        fake.setRows(new java.util.ArrayList<>(List.of(row)));
        when(jt.query(anyString(), any(ResultSetExtractor.class))).thenReturn(fake);

        SqlQueryResultVO result = service.executeAdhoc(1L, "SELECT t FROM x");

        // LocalDateTime 被格式化成无 T 的字符串
        assertEquals("2026-07-20 10:00:05", result.getRows().get(0).get("t"));
    }

    @Test
    void listDatabases_shouldReturnSchemaNames() {
        NamedParameterJdbcTemplate npt = mock(NamedParameterJdbcTemplate.class);
        JdbcTemplate jt = mock(JdbcTemplate.class);
        when(connectionManager.getTemplate(1L)).thenReturn(npt);
        when(npt.getJdbcOperations()).thenReturn(jt);
        when(jt.queryForList(anyString(), eq(String.class))).thenReturn(List.of("db_a", "db_b"));

        assertEquals(List.of("db_a", "db_b"), service.listDatabases(1L));
    }

    @Test
    void listTables_shouldBindDatabaseAsParam() {
        NamedParameterJdbcTemplate npt = mock(NamedParameterJdbcTemplate.class);
        JdbcTemplate jt = mock(JdbcTemplate.class);
        when(connectionManager.getTemplate(1L)).thenReturn(npt);
        when(npt.getJdbcOperations()).thenReturn(jt);
        when(jt.queryForList(anyString(), eq(String.class), eq("db_a"))).thenReturn(List.of("t_user"));

        assertEquals(List.of("t_user"), service.listTables(1L, "db_a"));
    }

    @Test
    void listTables_shouldRejectBlankDatabase_beforeTouchingDatasource() {
        BizException ex = assertThrows(BizException.class, () -> service.listTables(1L, "  "));
        assertEquals(ResultCode.PARAM_MISSING, ex.getResultCode());
        verify(connectionManager, never()).getTemplate(any());
    }

    private SqlDefineParam param(String name, String type, boolean required, String defaultValue,
                                 boolean isPageNum, boolean isPageSize, int sort) {
        SqlDefineParam param = new SqlDefineParam();
        param.setParamName(name);
        param.setParamType(type);
        param.setRequired(required ? 1 : 0);
        param.setDefaultValue(defaultValue);
        param.setIsPageNum(isPageNum ? 1 : 0);
        param.setIsPageSize(isPageSize ? 1 : 0);
        param.setSort(sort);
        return param;
    }
}
