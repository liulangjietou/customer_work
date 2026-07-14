package com.richard.fyoung.customeradmin.sqlconfig.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlDatasourceSaveRequest;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlDatasourceVO;
import com.richard.fyoung.customeradmin.sqlconfig.engine.SqlDatasourceConnectionManager;
import com.richard.fyoung.customeradmin.sqlconfig.entity.SqlDatasource;
import com.richard.fyoung.customeradmin.sqlconfig.entity.SqlDefine;
import com.richard.fyoung.customeradmin.sqlconfig.mapper.SqlDatasourceMapper;
import com.richard.fyoung.customeradmin.sqlconfig.mapper.SqlDefineMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SqlDatasourceService} 单测：密码加密落库/留空不改/脱敏回显、引用校验、连接池失效。
 * @author owlzhangfq@gmail.com
 */
class SqlDatasourceServiceTest {

    private SqlDatasourceMapper datasourceMapper;
    private SqlDefineMapper defineMapper;
    private SqlDatasourceConnectionManager connectionManager;
    private AesGcmCryptoUtil cryptoUtil;
    private SqlDatasourceService service;

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), SqlDatasource.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), SqlDefine.class);
    }

    @BeforeEach
    void setUp() {
        datasourceMapper = mock(SqlDatasourceMapper.class);
        defineMapper = mock(SqlDefineMapper.class);
        connectionManager = mock(SqlDatasourceConnectionManager.class);
        cryptoUtil = new AesGcmCryptoUtil("0123456789abcdef");
        service = new SqlDatasourceService(datasourceMapper, defineMapper, cryptoUtil, connectionManager);
    }

    @Test
    void create_shouldEncryptPassword_notStorePlainText() {
        SqlDatasourceSaveRequest request = new SqlDatasourceSaveRequest(
            "prod-db", "jdbc:mysql://x/db", "reader", "plain-secret", true, null);

        ArgumentCaptor<SqlDatasource> captor = ArgumentCaptor.forClass(SqlDatasource.class);
        service.create(request);

        verify(datasourceMapper).insert(captor.capture());
        assertNotEquals("plain-secret", captor.getValue().getPassword());
        assertEquals("plain-secret", cryptoUtil.decrypt(captor.getValue().getPassword()));
    }

    @Test
    void create_shouldRejectMissingPassword() {
        SqlDatasourceSaveRequest request = new SqlDatasourceSaveRequest(
            "prod-db", "jdbc:mysql://x/db", "reader", "", true, null);
        assertThrows(BizException.class, () -> service.create(request));
    }

    @Test
    void update_shouldKeepOldPassword_whenBlank_andEvictPool() {
        SqlDatasource existing = new SqlDatasource();
        existing.setId(1L);
        String originalCipher = cryptoUtil.encrypt("old-secret");
        existing.setPassword(originalCipher);
        when(datasourceMapper.selectById(1L)).thenReturn(existing);

        SqlDatasourceSaveRequest request = new SqlDatasourceSaveRequest(
            "prod-db", "jdbc:mysql://x/db", "reader", "", true, null);
        service.update(1L, request);

        ArgumentCaptor<SqlDatasource> captor = ArgumentCaptor.forClass(SqlDatasource.class);
        verify(datasourceMapper).updateById(captor.capture());
        assertEquals(originalCipher, captor.getValue().getPassword());
        verify(connectionManager).evict(1L);
    }

    @Test
    void get_shouldReturnMaskedPassword() {
        SqlDatasource existing = new SqlDatasource();
        existing.setId(1L);
        existing.setPassword(cryptoUtil.encrypt("super-secret-9999"));
        existing.setEnabled(1);
        when(datasourceMapper.selectById(1L)).thenReturn(existing);

        SqlDatasourceVO vo = service.get(1L);

        assertNotEquals("super-secret-9999", vo.getPasswordMasked());
        assertTrue(vo.getPasswordMasked().endsWith("9999"));
        assertTrue(vo.getEnabled());
    }

    @Test
    void delete_shouldReject_whenReferencedByDefine() {
        SqlDatasource existing = new SqlDatasource();
        existing.setId(1L);
        when(datasourceMapper.selectById(1L)).thenReturn(existing);
        when(defineMapper.exists(any(LambdaQueryWrapper.class))).thenReturn(true);

        assertThrows(BizException.class, () -> service.delete(1L));
    }

    @Test
    void delete_shouldEvictPool_whenNotReferenced() {
        SqlDatasource existing = new SqlDatasource();
        existing.setId(1L);
        when(datasourceMapper.selectById(1L)).thenReturn(existing);
        when(defineMapper.exists(any(LambdaQueryWrapper.class))).thenReturn(false);

        service.delete(1L);

        verify(datasourceMapper).deleteById(1L);
        verify(connectionManager).evict(1L);
    }
}
