package com.richard.fyoung.customeradmin.aiconfig.secret.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.secret.domain.SecretMaterialStatus;
import com.richard.fyoung.customeradmin.aiconfig.secret.domain.SecretProviderType;
import com.richard.fyoung.customeradmin.aiconfig.secret.domain.SecretRefStatus;
import com.richard.fyoung.customeradmin.aiconfig.secret.entity.AiSecretMaterial;
import com.richard.fyoung.customeradmin.aiconfig.secret.entity.AiSecretRef;
import com.richard.fyoung.customeradmin.aiconfig.secret.mapper.AiSecretMaterialMapper;
import com.richard.fyoung.customeradmin.aiconfig.secret.mapper.AiSecretRefMapper;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** SecretRef 双读、租户边界、轮换版本和无明文返回测试。 */
class SecretRefServiceTest {

    private AiSecretRefMapper refMapper;
    private AiSecretMaterialMapper materialMapper;
    private AesGcmCryptoUtil cryptoUtil;
    private SecretRefService service;

    @BeforeEach
    void setUp() {
        refMapper = mock(AiSecretRefMapper.class);
        materialMapper = mock(AiSecretMaterialMapper.class);
        cryptoUtil = new AesGcmCryptoUtil("0123456789abcdef");
        service = new SecretRefService(refMapper, materialMapper, cryptoUtil);
    }

    @Test
    void resolvePlaintext_shouldPreferCurrentSecretRefVersionOverLegacyCiphertext() {
        AiSecretRef ref = ref(7L, "tenant-a", 2, LocalDateTime.now().plusDays(1));
        AiSecretMaterial material = new AiSecretMaterial();
        material.setCipherText(cryptoUtil.encrypt("new-secret"));
        when(refMapper.selectOne(any(QueryWrapper.class))).thenReturn(ref);
        when(materialMapper.selectOne(any(QueryWrapper.class))).thenReturn(material);
        AiModelConfig model = model(7L, "tenant-a", cryptoUtil.encrypt("old-secret"));

        assertEquals("new-secret", service.resolvePlaintext(model));

        ArgumentCaptor<QueryWrapper<AiSecretRef>> refQuery = queryCaptor();
        verify(refMapper).selectOne(refQuery.capture());
        refQuery.getValue().getSqlSegment();
        assertTrue(refQuery.getValue().getParamNameValuePairs().containsValue("tenant-a"));
        ArgumentCaptor<QueryWrapper<AiSecretMaterial>> materialQuery = queryCaptor();
        verify(materialMapper).selectOne(materialQuery.capture());
        materialQuery.getValue().getSqlSegment();
        assertTrue(materialQuery.getValue().getParamNameValuePairs().containsValue(2));
        assertTrue(materialQuery.getValue().getParamNameValuePairs().containsValue("tenant-a"));
    }

    @Test
    void resolveCipherText_shouldFallBackToLegacyColumnWhenSecretRefMissing() {
        String legacyCipher = cryptoUtil.encrypt("legacy-secret");

        assertEquals(legacyCipher, service.resolveCipherText(null, "tenant-a", legacyCipher));
        verifyNoInteractions(refMapper, materialMapper);
    }

    @Test
    void resolveCipherText_shouldRejectExpiredReferenceBeforeReadingMaterial() {
        when(refMapper.selectOne(any(QueryWrapper.class)))
            .thenReturn(ref(7L, "tenant-a", 1, LocalDateTime.now().minusSeconds(1)));

        assertThrows(BizException.class,
            () -> service.resolveCipherText(7L, "tenant-a", cryptoUtil.encrypt("legacy-secret")));
        verify(materialMapper, never()).selectOne(any());
    }

    @Test
    void rotateLocal_shouldSupersedeOldVersionAndReturnMetadataOnly() {
        AiSecretRef ref = ref(7L, "tenant-a", 1, null);
        when(refMapper.selectOne(any(QueryWrapper.class))).thenReturn(ref);
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(30);
        ArgumentCaptor<AiSecretMaterial> inserted = ArgumentCaptor.forClass(AiSecretMaterial.class);

        SecretRefService.SecretWriteResult result =
            service.rotateLocal(7L, "tenant-a", "rotated-secret", expiresAt);

        verify(materialMapper).update(isNull(), any(Wrapper.class));
        verify(materialMapper).insert(inserted.capture());
        assertEquals(2, inserted.getValue().getVersion());
        assertEquals(SecretMaterialStatus.ACTIVE.name(), inserted.getValue().getStatus());
        assertEquals("rotated-secret", cryptoUtil.decrypt(inserted.getValue().getCipherText()));
        verify(refMapper).updateById(ref);
        assertEquals(2, result.version());
        assertEquals(2, result.metadata().currentVersion());
        assertEquals(expiresAt, result.metadata().expiresAt());
        assertFalse(result.metadata().toString().contains("rotated-secret"));
        assertFalse(result.metadata().toString().contains(result.cipherText()));
    }

    @Test
    void createLocal_shouldPersistCiphertextAndRotationAuditMetadata() {
        doAnswer(invocation -> {
            ((AiSecretRef) invocation.getArgument(0)).setId(9L);
            return 1;
        }).when(refMapper).insert(any(AiSecretRef.class));
        ArgumentCaptor<AiSecretMaterial> inserted = ArgumentCaptor.forClass(AiSecretMaterial.class);
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(7);

        SecretRefService.SecretWriteResult result =
            service.createLocal("tenant-a", "production key", "plain-secret", expiresAt);

        verify(materialMapper).insert(inserted.capture());
        assertEquals(9L, inserted.getValue().getSecretRefId());
        assertEquals("plain-secret", cryptoUtil.decrypt(inserted.getValue().getCipherText()));
        assertEquals(SecretRefStatus.ACTIVE.name(), result.metadata().status());
        assertEquals(1, result.metadata().currentVersion());
        assertEquals(expiresAt, result.metadata().expiresAt());
        assertTrue(result.metadata().lastRotatedAt() != null);
        assertFalse(result.metadata().toString().contains("plain-secret"));
    }

    private AiSecretRef ref(Long id, String tenantId, int version, LocalDateTime expiresAt) {
        AiSecretRef ref = new AiSecretRef();
        ref.setId(id);
        ref.setTenantId(tenantId);
        ref.setRefCode("model-ref");
        ref.setProviderType(SecretProviderType.LOCAL_AES.name());
        ref.setCurrentVersion(version);
        ref.setStatus(SecretRefStatus.ACTIVE.name());
        ref.setExpiresAt(expiresAt);
        ref.setLastRotatedAt(LocalDateTime.now());
        return ref;
    }

    private AiModelConfig model(Long refId, String tenantId, String legacyCipher) {
        AiModelConfig model = new AiModelConfig();
        model.setSecretRefId(refId);
        model.setTenantId(tenantId);
        model.setApiKey(legacyCipher);
        return model;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> ArgumentCaptor<QueryWrapper<T>> queryCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(QueryWrapper.class);
    }
}
