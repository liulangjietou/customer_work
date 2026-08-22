package com.richard.fyoung.customeradmin.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.auth.service.SessionRevocationService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.dto.TenantSaveRequest;
import com.richard.fyoung.customeradmin.tenant.entity.SysTenant;
import com.richard.fyoung.customeradmin.tenant.entity.TenantStatus;
import com.richard.fyoung.customeradmin.tenant.mapper.SysTenantMapper;
import com.richard.fyoung.customeradmin.tenant.access.service.TenantAccessPublishTaskService;
import com.richard.fyoung.customeradmin.tenant.access.TenantAccessDeliveryPlan;
import com.richard.fyoung.customeradmin.tenant.access.TenantAccessOperation;
import com.richard.fyoung.customeradmin.tenant.access.TenantChannelDisableService;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    private TenantAccessPublishTaskService accessPublishTaskService;
    private SessionRevocationService sessionRevocationService;
    private TenantChannelDisableService channelDisableService;
    private TenantService service;

    @BeforeEach
    void setUp() {
        tenantMapper = mock(SysTenantMapper.class);
        provisionService = mock(TenantProvisionService.class);
        accessPublishTaskService = mock(TenantAccessPublishTaskService.class);
        sessionRevocationService = mock(SessionRevocationService.class);
        channelDisableService = mock(TenantChannelDisableService.class);
        service = new TenantService(
            tenantMapper, provisionService, accessPublishTaskService,
            sessionRevocationService, channelDisableService);
    }

    private SysTenant tenant(Long id, String code, TenantStatus status) {
        SysTenant entity = new SysTenant();
        entity.setId(id);
        entity.setTenantCode(code);
        entity.setTenantName(code + " 名称");
        entity.setStatus(status.name());
        entity.setAccessEpoch(0L);
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
        request.setTenantCode("AcMe");
        request.setTenantName("Acme");
        service.create(request);

        // 不初始化角色的新租户是个空壳；编码统一小写，避免数据库与缓存采用不同大小写语义
        verify(provisionService).provision("acme");
        verify(accessPublishTaskService).enqueue(any(), any(TenantAccessDeliveryPlan.class));
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
    void update_shouldRotateAccessEpochWhenExpiryChanges() {
        SysTenant existing = tenant(9L, "acme", TenantStatus.ACTIVE);
        SysTenant changed = tenant(9L, "acme", TenantStatus.ACTIVE);
        changed.setAccessEpoch(1L);
        LocalDateTime expiry = LocalDateTime.now().plusDays(30);
        changed.setExpireTime(expiry);
        when(tenantMapper.selectById(9L)).thenReturn(existing, changed);
        when(tenantMapper.incrementAccessEpoch(9L)).thenReturn(1);

        TenantSaveRequest request = new TenantSaveRequest();
        request.setId(9L);
        request.setTenantCode("acme");
        request.setTenantName("Acme");
        request.setExpireTime(expiry);

        service.update(request);

        verify(tenantMapper).incrementAccessEpoch(9L);
        verify(accessPublishTaskService).enqueue(any(), any(TenantAccessDeliveryPlan.class));
        verify(sessionRevocationService).revokeTenantAfterCommit("acme");
    }

    @Test
    void changeStatus_shouldProtectDefaultTenant() {
        when(tenantMapper.selectById(1L)).thenReturn(tenant(1L, TenantContext.DEFAULT, TenantStatus.ACTIVE));

        assertEquals(ResultCode.TENANT_RESERVED_PROTECTED,
            assertThrows(BizException.class, () -> service.changeStatus(1L, TenantStatus.SUSPENDED)).getResultCode(),
            "冻结 default 会让存量数据整体不可访问");
    }

    @Test
    void delete_shouldProtectReservedTenants() {
        when(tenantMapper.selectById(1L)).thenReturn(tenant(1L, "DEFAULT", TenantStatus.ACTIVE));
        assertThrows(BizException.class, () -> service.delete(1L));
        verify(tenantMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void changeStatus_shouldAllowNormalTenant() {
        SysTenant active = tenant(9L, "acme", TenantStatus.ACTIVE);
        SysTenant suspended = tenant(9L, "acme", TenantStatus.SUSPENDED);
        suspended.setAccessEpoch(1L);
        when(tenantMapper.selectById(9L)).thenReturn(active, suspended);
        when(tenantMapper.updateStatusAndIncrementAccessEpoch(9L, TenantStatus.SUSPENDED.name())).thenReturn(1);
        service.changeStatus(9L, TenantStatus.SUSPENDED);
        verify(tenantMapper).updateStatusAndIncrementAccessEpoch(9L, TenantStatus.SUSPENDED.name());
        verify(accessPublishTaskService).enqueue(any(), any(TenantAccessDeliveryPlan.class));
        verify(channelDisableService, never()).disableForOffboarding(any());
        verify(sessionRevocationService).revokeTenantAfterCommit("acme");
    }

    @Test
    void changeStatus_shouldRejectLeavingTerminatedState() {
        when(tenantMapper.selectById(9L)).thenReturn(tenant(9L, "acme", TenantStatus.TERMINATED));

        BizException exception = assertThrows(BizException.class,
            () -> service.changeStatus(9L, TenantStatus.ACTIVE));

        assertEquals(ResultCode.PARAM_INVALID, exception.getResultCode());
        verify(tenantMapper, never()).updateStatusAndIncrementAccessEpoch(any(), any());
    }

    @Test
    void changeStatus_terminateShouldDisableChannelsAndPersistOffboardingPlan() {
        SysTenant active = tenant(9L, "acme", TenantStatus.ACTIVE);
        SysTenant terminated = tenant(9L, "acme", TenantStatus.TERMINATED);
        terminated.setAccessEpoch(1L);
        when(tenantMapper.selectById(9L)).thenReturn(active, terminated);
        when(tenantMapper.updateStatusAndIncrementAccessEpoch(9L, TenantStatus.TERMINATED.name()))
            .thenReturn(1);
        when(channelDisableService.disableForOffboarding("acme")).thenReturn(3);

        service.changeStatus(9L, TenantStatus.TERMINATED);

        verify(channelDisableService).disableForOffboarding("acme");
        ArgumentCaptor<TenantAccessDeliveryPlan> planCaptor =
            ArgumentCaptor.forClass(TenantAccessDeliveryPlan.class);
        verify(accessPublishTaskService).enqueue(any(), planCaptor.capture());
        assertEquals(TenantAccessOperation.OFFBOARD, planCaptor.getValue().operation());
        assertEquals(3, planCaptor.getValue().channelsDisabledCount());
        verify(sessionRevocationService).revokeTenantAfterCommit("acme");
    }

    @Test
    void revokeSessions_shouldRotateEpochWithoutChangingStatus() {
        SysTenant before = tenant(9L, "acme", TenantStatus.ACTIVE);
        SysTenant after = tenant(9L, "acme", TenantStatus.ACTIVE);
        after.setAccessEpoch(1L);
        when(tenantMapper.selectById(9L)).thenReturn(before, after);
        when(tenantMapper.incrementAccessEpoch(9L)).thenReturn(1);

        service.revokeSessions(9L);

        verify(tenantMapper).incrementAccessEpoch(9L);
        verify(accessPublishTaskService).enqueue(any(), any(TenantAccessDeliveryPlan.class));
        verify(sessionRevocationService).revokeTenantAfterCommit("acme");
    }

    @Test
    void revokeSessions_shouldProtectDefaultTenant() {
        when(tenantMapper.selectById(1L)).thenReturn(tenant(1L, TenantContext.DEFAULT, TenantStatus.ACTIVE));

        BizException exception = assertThrows(BizException.class, () -> service.revokeSessions(1L));

        assertEquals(ResultCode.TENANT_RESERVED_PROTECTED, exception.getResultCode());
        verify(tenantMapper, never()).incrementAccessEpoch(1L);
    }

    @Test
    void delete_shouldKeepTenantRecordForOffboardingAudit() {
        SysTenant active = tenant(9L, "acme", TenantStatus.ACTIVE);
        SysTenant terminated = tenant(9L, "acme", TenantStatus.TERMINATED);
        terminated.setAccessEpoch(1L);
        when(tenantMapper.selectById(9L)).thenReturn(active, terminated);
        when(tenantMapper.updateStatusAndIncrementAccessEpoch(9L, TenantStatus.TERMINATED.name()))
            .thenReturn(1);
        when(channelDisableService.disableForOffboarding("acme")).thenReturn(2);

        service.delete(9L);

        verify(tenantMapper, never()).deleteById(9L);
        verify(channelDisableService).disableForOffboarding("acme");
        verify(accessPublishTaskService).enqueue(any(), any(TenantAccessDeliveryPlan.class));
        verify(sessionRevocationService).revokeTenantAfterCommit("acme");
    }

    @Test
    void assertAccessible_shouldAlwaysAllowDefault() {
        assertDoesNotThrow(() -> service.assertAccessible("DEFAULT"),
            "default 承担系统保留数据与控制面入口，不受租户生命周期约束");
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
        assertTrue(service.existsAccessible(TenantContext.DEFAULT), "default 视角恒可用");
    }

    @Test
    void resolveAccessibleCode_shouldReturnStoredTenantCode() {
        when(tenantMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(tenant(1L, "AcMe", TenantStatus.ACTIVE));

        assertEquals("AcMe", service.resolveAccessibleCode("acme"),
            "请求别名只用于数据库定位，会话必须保存数据库里的权威编码");
        assertEquals(TenantContext.DEFAULT, service.resolveAccessibleCode("DEFAULT"));
    }
}
