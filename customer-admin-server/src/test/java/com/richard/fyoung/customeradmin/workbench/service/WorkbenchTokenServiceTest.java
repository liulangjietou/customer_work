package com.richard.fyoung.customeradmin.workbench.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchTokenCreateRequest;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchTokenCreatedVO;
import com.richard.fyoung.customeradmin.workbench.entity.WorkbenchToken;
import com.richard.fyoung.customeradmin.workbench.mapper.WorkbenchTokenMapper;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkbenchTokenService} 单测：明文不落库（只存 SHA-256）、令牌前缀、
 * 校验命中/吊销/过期/不存在、越权吊销的 fast fail。Mapper 用 mock，不依赖真实库。
 * @author owlzhangfq@gmail.com
 */
class WorkbenchTokenServiceTest {

    private static final long USER_ID = 100L;

    private WorkbenchTokenMapper tokenMapper;
    private WorkbenchTokenService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), WorkbenchToken.class);
    }

    @BeforeEach
    void setUp() {
        tokenMapper = mock(WorkbenchTokenMapper.class);
        service = new WorkbenchTokenService(tokenMapper);
    }

    @Test
    void createToken_shouldReturnPlaintextOnce_andStoreOnlyHash() {
        WorkbenchTokenCreatedVO vo = service.createToken(USER_ID, new WorkbenchTokenCreateRequest("脚本用", 30));

        assertNotNull(vo.getToken());
        assertTrue(vo.getToken().startsWith("wbt_"), "令牌明文应带 wbt_ 前缀");

        ArgumentCaptor<WorkbenchToken> captor = ArgumentCaptor.forClass(WorkbenchToken.class);
        verify(tokenMapper).insert(captor.capture());
        WorkbenchToken saved = captor.getValue();
        // 库里存的是 64 位十六进制哈希，绝不等于明文
        assertEquals(64, saved.getTokenHash().length());
        assertTrue(!saved.getTokenHash().equals(vo.getToken()), "落库必须是哈希而非明文");
        assertEquals(vo.getToken().substring(0, 12), saved.getTokenPrefix());
        assertEquals(USER_ID, saved.getUserId());
        assertNotNull(saved.getExpireTime());
    }

    @Test
    void createToken_shouldSetNullExpire_whenExpireDaysNull() {
        service.createToken(USER_ID, new WorkbenchTokenCreateRequest("永久", null));

        ArgumentCaptor<WorkbenchToken> captor = ArgumentCaptor.forClass(WorkbenchToken.class);
        verify(tokenMapper).insert(captor.capture());
        assertEquals(null, captor.getValue().getExpireTime(), "expireDays 为 null 应永不过期");
    }

    @Test
    void validate_shouldReturnPrincipal_andRefreshLastUsed_whenValid() {
        // 先造一个令牌拿到明文，再用其哈希构造 selectOne 返回
        WorkbenchTokenCreatedVO created = createAndCapture();
        String rawToken = created.getToken();
        WorkbenchToken stored = capturedToken();
        stored.setRevoked(0);
        stored.setTenantId(TenantContext.DEFAULT);
        when(tokenMapper.selectOne(any())).thenReturn(stored);

        WorkbenchTokenService.Principal principal = service.validate(rawToken);

        assertEquals(USER_ID, principal.userId());
        verify(tokenMapper).updateById(any(WorkbenchToken.class)); // 刷新 last_used
    }

    @Test
    void validate_shouldThrowUnauthorized_whenNotFound() {
        when(tokenMapper.selectOne(any())).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.validate("wbt_missing"));
        assertEquals(ResultCode.UNAUTHORIZED, ex.getResultCode());
    }

    @Test
    void validate_shouldThrowUnauthorized_whenRevoked() {
        WorkbenchToken revoked = new WorkbenchToken();
        revoked.setUserId(USER_ID);
        revoked.setRevoked(1);
        when(tokenMapper.selectOne(any())).thenReturn(revoked);

        BizException ex = assertThrows(BizException.class, () -> service.validate("wbt_revoked"));
        assertEquals(ResultCode.UNAUTHORIZED, ex.getResultCode());
    }

    @Test
    void validate_shouldThrowExpired_whenPastExpireTime() {
        WorkbenchToken expired = new WorkbenchToken();
        expired.setUserId(USER_ID);
        expired.setRevoked(0);
        expired.setExpireTime(LocalDateTime.now().minusDays(1));
        when(tokenMapper.selectOne(any())).thenReturn(expired);

        BizException ex = assertThrows(BizException.class, () -> service.validate("wbt_expired"));
        assertEquals(ResultCode.TOKEN_EXPIRED, ex.getResultCode());
    }

    @Test
    void validate_shouldThrowUnauthorized_whenTokenBlank() {
        BizException ex = assertThrows(BizException.class, () -> service.validate("  "));
        assertEquals(ResultCode.UNAUTHORIZED, ex.getResultCode());
    }

    @Test
    void validate_shouldRejectPersistedTokenWithInvalidTenant() {
        WorkbenchToken token = new WorkbenchToken();
        token.setUserId(USER_ID);
        token.setRevoked(0);
        token.setTenantId("_legacy");
        when(tokenMapper.selectOne(any())).thenReturn(token);

        BizException ex = assertThrows(BizException.class, () -> service.validate("wbt_legacy"));

        assertEquals(ResultCode.UNAUTHORIZED, ex.getResultCode());
        verify(tokenMapper, never()).updateById(token);
    }

    @Test
    void revoke_shouldThrowForbidden_whenNotOwner() {
        WorkbenchToken other = new WorkbenchToken();
        other.setId(1L);
        other.setUserId(999L);
        when(tokenMapper.selectById(1L)).thenReturn(other);

        BizException ex = assertThrows(BizException.class, () -> service.revoke(USER_ID, 1L));
        assertEquals(ResultCode.FORBIDDEN, ex.getResultCode());
        verify(tokenMapper, never()).updateById(any(WorkbenchToken.class));
    }

    @Test
    void revoke_shouldSetRevoked_whenOwner() {
        WorkbenchToken own = new WorkbenchToken();
        own.setId(1L);
        own.setUserId(USER_ID);
        own.setRevoked(0);
        when(tokenMapper.selectById(1L)).thenReturn(own);

        service.revoke(USER_ID, 1L);

        ArgumentCaptor<WorkbenchToken> captor = ArgumentCaptor.forClass(WorkbenchToken.class);
        verify(tokenMapper).updateById(captor.capture());
        assertEquals(1, captor.getValue().getRevoked());
    }

    // ---- helpers ----

    private WorkbenchToken lastCaptured;

    private WorkbenchTokenCreatedVO createAndCapture() {
        WorkbenchTokenCreatedVO vo = service.createToken(USER_ID, new WorkbenchTokenCreateRequest("脚本用", 30));
        ArgumentCaptor<WorkbenchToken> captor = ArgumentCaptor.forClass(WorkbenchToken.class);
        verify(tokenMapper).insert(captor.capture());
        lastCaptured = captor.getValue();
        return vo;
    }

    private WorkbenchToken capturedToken() {
        return lastCaptured;
    }
}
