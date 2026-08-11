package com.richard.fyoung.customeradmin.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.dto.TenantSaveRequest;
import com.richard.fyoung.customeradmin.tenant.entity.SysTenant;
import com.richard.fyoung.customeradmin.tenant.entity.TenantStatus;
import com.richard.fyoung.customeradmin.tenant.mapper.SysTenantMapper;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TenantService} 单测：保留租户保护、编码不可变、生命周期与可访问性判定。
 * @author owlzhangfq@gmail.com
 */
class TenantServiceTest {

    private SysTenantMapper tenantMapper;
    private TenantProvisionService provisionService;
    private TenantService service;

    @BeforeEach
    void setUp() {
        tenantMapper = mock(SysTenantMapper.class);
        provisionService = mock(TenantProvisionService.class);
        service = new TenantService(tenantMapper, provisionService);
    }

    private SysTenant tenant(Long id, String code, TenantStatus status) {
        SysTenant entity = new SysTenant();
        entity.setId(id);
        entity.setTenantCode(code);
        entity.setTenantName(code + " 名称");
        entity.setStatus(status.name());
        return entity;
    }

    @Test
    void create_shouldRejectDuplicateCode() {
        when(tenantMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(tenant(1L, "acme", TenantStatus.ACTIVE));

        TenantSaveRequest request = new TenantSaveRequest();
        request.setTenantCode("acme");
        request.setTenantName("重复租户");

        BizException e = assertThrows(BizException.class, () -> service.create(request));
        assertEquals(ResultCode.TENANT_CODE_DUPLICATE, e.getResultCode(), "编码重复应拦在插入之前");
        verify(tenantMapper, never()).insert(any(SysTenant.class));
    }

    @Test
    void create_shouldProvisionNewTenant() {
        when(tenantMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        TenantSaveRequest request = new TenantSaveRequest();
        request.setTenantCode("acme");
        request.setTenantName("Acme");
        service.create(request);

        // 不初始化角色的新租户是个空壳：管理员建了也没有任何角色可分配
        verify(provisionService).provision("acme");
    }

    @Test
    void update_shouldRejectCodeChange() {
        when(tenantMapper.selectById(1L)).thenReturn(tenant(1L, "acme", TenantStatus.ACTIVE));

        TenantSaveRequest request = new TenantSaveRequest();
        request.setId(1L);
        request.setTenantCode("acme-renamed");
        request.setTenantName("Acme");

        BizException e = assertThrows(BizException.class, () -> service.update(request));
        assertEquals(ResultCode.TENANT_CODE_IMMUTABLE, e.getResultCode(),
            "编码是业务数据的归属标识，改了等于让存量数据失去归属");
    }

    @Test
    void changeStatus_shouldProtectReservedTenants() {
        when(tenantMapper.selectById(1L)).thenReturn(tenant(1L, TenantContext.DEFAULT, TenantStatus.ACTIVE));
        when(tenantMapper.selectById(2L)).thenReturn(tenant(2L, TenantContext.PLATFORM, TenantStatus.ACTIVE));

        assertEquals(ResultCode.TENANT_RESERVED_PROTECTED,
            assertThrows(BizException.class, () -> service.changeStatus(1L, TenantStatus.SUSPENDED)).getResultCode(),
            "冻结 default 会让存量数据整体不可访问");
        assertEquals(ResultCode.TENANT_RESERVED_PROTECTED,
            assertThrows(BizException.class, () -> service.changeStatus(2L, TenantStatus.SUSPENDED)).getResultCode(),
            "冻结平台租户等于运营方把自己锁在门外");
    }

    @Test
    void delete_shouldProtectReservedTenants() {
        when(tenantMapper.selectById(1L)).thenReturn(tenant(1L, TenantContext.PLATFORM, TenantStatus.ACTIVE));
        assertThrows(BizException.class, () -> service.delete(1L));
        verify(tenantMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void changeStatus_shouldAllowNormalTenant() {
        when(tenantMapper.selectById(9L)).thenReturn(tenant(9L, "acme", TenantStatus.ACTIVE));
        service.changeStatus(9L, TenantStatus.SUSPENDED);
        verify(tenantMapper).updateById(any(SysTenant.class));
    }

    @Test
    void assertAccessible_shouldAlwaysAllowPlatform() {
        assertDoesNotThrow(() -> service.assertAccessible(TenantContext.PLATFORM),
            "平台自身不受租户生命周期约束");
        verify(tenantMapper, never()).selectOne(any(LambdaQueryWrapper.class));
    }

    @Test
    void assertAccessible_shouldRejectUnknownTenant() {
        when(tenantMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        assertEquals(ResultCode.TENANT_NOT_FOUND,
            assertThrows(BizException.class, () -> service.assertAccessible("ghost")).getResultCode());
    }

    @Test
    void assertAccessible_shouldRejectSuspendedTenant() {
        when(tenantMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(tenant(1L, "acme", TenantStatus.SUSPENDED));
        assertEquals(ResultCode.TENANT_SUSPENDED,
            assertThrows(BizException.class, () -> service.assertAccessible("acme")).getResultCode(),
            "冻结即刻生效于登录，这是租户级熔断的落点");
    }

    @Test
    void assertAccessible_shouldRejectExpiredTenant() {
        SysTenant expired = tenant(1L, "acme", TenantStatus.ACTIVE);
        expired.setExpireTime(LocalDateTime.now().minusDays(1));
        when(tenantMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(expired);

        assertThrows(BizException.class, () -> service.assertAccessible("acme"), "已过期租户不应放行");
    }

    @Test
    void existsAccessible_shouldReflectStatus() {
        when(tenantMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(tenant(1L, "acme", TenantStatus.TERMINATED));
        assertFalse(service.existsAccessible("acme"), "已退租的租户不能作为切换目标");
        assertTrue(service.existsAccessible(TenantContext.PLATFORM), "平台视角恒可用");
    }
}
