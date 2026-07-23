package com.richard.fyoung.customeradmin.sqlconfig.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlDefineParamSaveRequest;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlDefineSaveRequest;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlFieldTransformSaveRequest;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SqlDefineService} 单测：保存只读校验、唯一键校验、级联删除、复制、子表参数/转换器校验。
 * @author owlzhangfq@gmail.com
 */
class SqlDefineServiceTest {

    private SqlDefineMapper defineMapper;
    private SqlDefineParamMapper paramMapper;
    private SqlFieldTransformMapper transformMapper;
    private SqlDatasourceMapper datasourceMapper;
    private SqlDefineService service;

    @BeforeAll
    static void initLambdaCache() {
        Configuration cfg = new Configuration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), SqlDefine.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), SqlDefineParam.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), SqlFieldTransform.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), SqlDatasource.class);
    }

    @BeforeEach
    void setUp() {
        defineMapper = mock(SqlDefineMapper.class);
        paramMapper = mock(SqlDefineParamMapper.class);
        transformMapper = mock(SqlFieldTransformMapper.class);
        datasourceMapper = mock(SqlDatasourceMapper.class);
        service = new SqlDefineService(defineMapper, paramMapper, transformMapper, datasourceMapper);
    }

    @Test
    void create_shouldRejectNonReadOnlySql() {
        SqlDefineSaveRequest request = new SqlDefineSaveRequest(
            "k1", 1L, "desc", "UPDATE t SET a = 1", null, false, true, null);
        BizException ex = assertThrows(BizException.class, () -> service.create(request));
        assertEquals(ResultCode.SQL_NOT_READONLY, ex.getResultCode());
    }

    @Test
    void create_shouldRejectDuplicateKey() {
        when(defineMapper.exists(any(LambdaQueryWrapper.class))).thenReturn(true);
        SqlDefineSaveRequest request = new SqlDefineSaveRequest(
            "k1", 1L, "desc", "SELECT 1", null, false, true, null);
        BizException ex = assertThrows(BizException.class, () -> service.create(request));
        assertEquals(ResultCode.RESOURCE_DUPLICATE, ex.getResultCode());
    }

    @Test
    void create_shouldSucceed_forReadOnlySql() {
        when(defineMapper.exists(any(LambdaQueryWrapper.class))).thenReturn(false);
        when(datasourceMapper.selectById(1L)).thenReturn(new SqlDatasource());
        SqlDefineSaveRequest request = new SqlDefineSaveRequest(
            "k1", 1L, "desc", "SELECT * FROM t WHERE a = :a", "SELECT count(*) FROM t WHERE a = :a", true, true, null);

        service.create(request);

        verify(defineMapper).insert(any(SqlDefine.class));
    }

    @Test
    void delete_shouldCascadeDeleteChildren() {
        when(defineMapper.selectById(1L)).thenReturn(new SqlDefine());

        service.delete(1L);

        verify(paramMapper).delete(any(LambdaQueryWrapper.class));
        verify(transformMapper).delete(any(LambdaQueryWrapper.class));
        verify(defineMapper).deleteById(1L);
    }

    @Test
    void copy_shouldCopyDefineWithSuffixKey_andChildren() {
        SqlDefine source = new SqlDefine();
        source.setId(1L);
        source.setDefineKey("orig");
        source.setQuerySql("SELECT 1");
        when(defineMapper.selectById(1L)).thenReturn(source);
        when(defineMapper.exists(any(LambdaQueryWrapper.class))).thenReturn(false);
        SqlDefineParam param = new SqlDefineParam();
        param.setParamName("a");
        param.setParamType("STRING");
        when(paramMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(param));
        SqlFieldTransform transform = new SqlFieldTransform();
        transform.setFieldName("f");
        transform.setTransformType("VALUE_MAP");
        when(transformMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(transform));

        service.copy(1L);

        ArgumentCaptor<SqlDefine> captor = ArgumentCaptor.forClass(SqlDefine.class);
        verify(defineMapper).insert(captor.capture());
        assertTrue(captor.getValue().getDefineKey().startsWith("orig-copy"));
        verify(paramMapper).insert(any(SqlDefineParam.class));
        verify(transformMapper).insert(any(SqlFieldTransform.class));
    }

    @Test
    void createParam_shouldRejectInvalidParamType() {
        when(defineMapper.selectById(1L)).thenReturn(new SqlDefine());
        SqlDefineParamSaveRequest request = new SqlDefineParamSaveRequest(
            "p", "desc", "BOOLEAN", null, false, null, null, false, false, 1);
        BizException ex = assertThrows(BizException.class, () -> service.createParam(1L, request));
        assertEquals(ResultCode.PARAM_INVALID, ex.getResultCode());
    }

    @Test
    void createParam_shouldRejectInvalidDropDownJson() {
        when(defineMapper.selectById(1L)).thenReturn(new SqlDefine());
        SqlDefineParamSaveRequest request = new SqlDefineParamSaveRequest(
            "p", "desc", "STRING", null, false, null, "not-json", false, false, 1);
        assertThrows(BizException.class, () -> service.createParam(1L, request));
    }

    @Test
    void createParam_shouldRejectDateFormatOnNonDatetimeParam() {
        when(defineMapper.selectById(1L)).thenReturn(new SqlDefine());
        SqlDefineParamSaveRequest request = new SqlDefineParamSaveRequest(
            "p", "desc", "STRING", "yyyy-MM-dd", false, null, null, false, false, 1);
        BizException ex = assertThrows(BizException.class, () -> service.createParam(1L, request));
        assertEquals(ResultCode.PARAM_INVALID, ex.getResultCode());
    }

    @Test
    void createParam_shouldRejectIllegalDateFormatPattern() {
        when(defineMapper.selectById(1L)).thenReturn(new SqlDefine());
        SqlDefineParamSaveRequest request = new SqlDefineParamSaveRequest(
            "p", "desc", "DATETIME", "yyyy-MM-dd bad{", false, null, null, false, false, 1);
        BizException ex = assertThrows(BizException.class, () -> service.createParam(1L, request));
        assertEquals(ResultCode.PARAM_INVALID, ex.getResultCode());
    }

    @Test
    void createParam_shouldAcceptDateFormatOnDatetimeParam() {
        when(defineMapper.selectById(1L)).thenReturn(new SqlDefine());
        SqlDefineParamSaveRequest request = new SqlDefineParamSaveRequest(
            "p", "desc", "DATETIME", "yyyy-MM-dd", false, null, null, false, false, 1);

        service.createParam(1L, request);

        ArgumentCaptor<SqlDefineParam> captor = ArgumentCaptor.forClass(SqlDefineParam.class);
        verify(paramMapper).insert(captor.capture());
        assertEquals("yyyy-MM-dd", captor.getValue().getDateFormat());
    }

    @Test
    void createTransform_shouldRejectInvalidValueMapJson() {
        when(defineMapper.selectById(1L)).thenReturn(new SqlDefine());
        SqlFieldTransformSaveRequest request = new SqlFieldTransformSaveRequest("f", "VALUE_MAP", "not-json");
        assertThrows(BizException.class, () -> service.createTransform(1L, request));
    }
}
