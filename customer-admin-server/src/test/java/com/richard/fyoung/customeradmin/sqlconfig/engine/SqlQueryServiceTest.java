package com.richard.fyoung.customeradmin.sqlconfig.engine;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.config.AdminSqlConfigProperties;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlQueryMetaVO;
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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SqlQueryService} 参数处理逻辑：类型转换、必填校验、pageNum→offset 换算、pageSize 钳制，
 * 以及 meta 元数据组装（默认值表达式解析、下拉解析）。
 * @author owlzhangfq@gmail.com
 */
class SqlQueryServiceTest {

    private SqlDefineMapper defineMapper;
    private SqlDefineParamMapper paramMapper;
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
        SqlFieldTransformMapper transformMapper = mock(SqlFieldTransformMapper.class);
        SqlDatasourceConnectionManager connectionManager = mock(SqlDatasourceConnectionManager.class);
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
