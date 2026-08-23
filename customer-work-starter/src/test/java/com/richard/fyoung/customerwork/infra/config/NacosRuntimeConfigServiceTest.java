package com.richard.fyoung.customerwork.infra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.data.outbox.OutboxService;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.alibaba.nacos.api.config.listener.Listener;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link NacosRuntimeConfigService} 单测：坏 JSON / 解密失败均不调用 applier（旧配置不被覆盖），
 * 合法配置解密后透传明文密钥给 applier。不依赖真实 Nacos。
 * @author owlzhangfq@gmail.com
 */
class NacosRuntimeConfigServiceTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private final ObjectMapper mapper = new ObjectMapper();

    private static String adminEncrypt(String plain, String key) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
            new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ct, 0, combined, iv.length, ct.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    private CustomerWorkProperties props() {
        CustomerWorkProperties p = new CustomerWorkProperties();
        p.getNacos().setConfigAesKey(KEY);
        return p;
    }

    @Test
    void validConfigDecryptsAndApplies() throws Exception {
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        when(applier.apply(any(), eq("sk-plain"), any())).thenReturn(true);
        NacosRuntimeConfigService service = new NacosRuntimeConfigService(props(), applier);

        CustomerWorkRuntimeConfig dto = new CustomerWorkRuntimeConfig();
        dto.getModel().setProvider("openai");
        dto.getModel().setName("gpt-4o");
        dto.getModel().setApiKeyCipher(adminEncrypt("sk-plain", KEY));
        String json = runtimeJson(dto);

        assertTrue(service.applyConfig(json));
        verify(applier).apply(any(), eq("sk-plain"), any());
    }

    @Test
    void changedContentHash_shouldInvalidateTenantCacheBeforeApply() throws Exception {
        CustomerWorkProperties properties = props();
        properties.getNacos().setTenantCode("tenant-a");
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        when(applier.apply(any(), any(), any())).thenReturn(true);
        RuntimeConfigCacheInvalidator invalidator = mock(RuntimeConfigCacheInvalidator.class);
        AtomicReference<String> invalidatedTenant = new AtomicReference<>();
        doAnswer(invocation -> {
            invalidatedTenant.set(TenantContext.require());
            return null;
        }).when(invalidator).beginTransition(anyString());
        NacosRuntimeConfigService service =
            new NacosRuntimeConfigService(properties, applier, null, invalidator);

        CustomerWorkRuntimeConfig dto = new CustomerWorkRuntimeConfig();

        assertTrue(service.applyConfig(runtimeJson(dto)));
        InOrder order = inOrder(invalidator, applier);
        order.verify(invalidator).beginTransition(anyString());
        order.verify(applier).apply(any(), any(), any());
        order.verify(invalidator).commitTransition(anyString());
        assertEquals("tenant-a", invalidatedTenant.get());
        assertFalse(TenantContext.isPresent(), "失效完成后必须恢复原租户上下文");
    }

    @Test
    void sameContentHash_shouldNotInvalidateAgain() throws Exception {
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        when(applier.apply(any(), any(), any())).thenReturn(true);
        RuntimeConfigCacheInvalidator invalidator = mock(RuntimeConfigCacheInvalidator.class);
        NacosRuntimeConfigService service =
            new NacosRuntimeConfigService(props(), applier, null, invalidator);
        CustomerWorkRuntimeConfig first = new CustomerWorkRuntimeConfig();
        first.setRevision("rev-1");
        CustomerWorkRuntimeConfig sameContent = new CustomerWorkRuntimeConfig();
        sameContent.setRevision("rev-2");
        String firstJson = runtimeJson(first);
        String sameContentJson = runtimeJson(sameContent);

        assertEquals(first.getContentHash(), sameContent.getContentHash());
        assertTrue(service.applyConfig(firstJson));
        assertTrue(service.applyConfig(sameContentJson));

        verify(invalidator, times(1)).beginTransition(anyString());
        verify(applier, times(2)).apply(any(), any(), any());
        assertEquals("rev-2", service.activeRevision());
        assertEquals(first.getContentHash(), service.activeContentHash());
    }

    @Test
    void invalidationFailure_shouldRejectBeforeApplyAndKeepActiveIdentity() throws Exception {
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        when(applier.apply(any(), any(), any())).thenReturn(true);
        RuntimeConfigCacheInvalidator invalidator = mock(RuntimeConfigCacheInvalidator.class);
        NacosRuntimeConfigService service =
            new NacosRuntimeConfigService(props(), applier, null, invalidator);
        CustomerWorkRuntimeConfig active = new CustomerWorkRuntimeConfig();
        active.setRevision("rev-ok");
        assertTrue(service.applyConfig(runtimeJson(active)));
        String activeHash = active.getContentHash();
        doThrow(new IllegalStateException("database unavailable"))
            .when(invalidator).beginTransition(anyString());
        CustomerWorkRuntimeConfig next = new CustomerWorkRuntimeConfig();
        next.setRevision("rev-next");
        next.setSystemPrompt("changed prompt");

        assertFalse(service.applyConfig(runtimeJson(next)));

        verify(applier, times(1)).apply(any(), any(), any());
        assertEquals("rev-ok", service.activeRevision());
        assertEquals(activeHash, service.activeContentHash());
        assertFalse(TenantContext.isPresent(), "异常路径也必须恢复原租户上下文");
    }

    @Test
    void applierRejection_shouldRollbackCacheGenerationAndKeepActiveIdentity() throws Exception {
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        when(applier.apply(any(), any(), any())).thenReturn(false);
        RuntimeConfigCacheInvalidator invalidator = mock(RuntimeConfigCacheInvalidator.class);
        NacosRuntimeConfigService service =
            new NacosRuntimeConfigService(props(), applier, null, invalidator);
        CustomerWorkRuntimeConfig rejected = new CustomerWorkRuntimeConfig();
        rejected.setRevision("rev-rejected");

        assertFalse(service.applyConfig(runtimeJson(rejected)));

        InOrder order = inOrder(invalidator, applier);
        order.verify(invalidator).beginTransition(rejected.getContentHash());
        order.verify(applier).apply(any(), any(), any());
        order.verify(invalidator).rollbackTransition(rejected.getContentHash());
        verify(invalidator, never()).commitTransition(anyString());
        assertEquals("", service.activeRevision());
        assertEquals("", service.activeContentHash());
    }

    @Test
    void routingConfig_shouldDecryptEveryDeploymentBeforeInvalidationAndAtomicApply() throws Exception {
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        when(applier.apply(any(), any(), any(), any(), any())).thenReturn(true);
        RuntimeConfigCacheInvalidator invalidator = mock(RuntimeConfigCacheInvalidator.class);
        NacosRuntimeConfigService service =
            new NacosRuntimeConfigService(props(), applier, null, invalidator);
        CustomerWorkRuntimeConfig dto = routingConfig(
            adminEncrypt("sk-primary", KEY), adminEncrypt("sk-fallback", KEY));
        dto.setRevision("route-rev-1");

        assertTrue(service.applyConfig(runtimeJson(dto)));

        InOrder order = inOrder(invalidator, applier);
        order.verify(invalidator).beginTransition(anyString());
        ArgumentCaptor<Map<Long, String>> keys = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<Long, String>> experimentKeys = ArgumentCaptor.forClass(Map.class);
        order.verify(applier).apply(any(), any(), any(), keys.capture(), experimentKeys.capture());
        assertEquals(Map.of(10L, "sk-primary", 20L, "sk-fallback"), keys.getValue());
        assertTrue(experimentKeys.getValue().isEmpty());
        assertEquals("route-rev-1", service.activeRevision());
    }

    @Test
    void routingDecryptFailure_shouldKeepPreviousIdentityAndNeverInvalidateOrSwitch() throws Exception {
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        when(applier.apply(any(), any(), any())).thenReturn(true);
        RuntimeConfigCacheInvalidator invalidator = mock(RuntimeConfigCacheInvalidator.class);
        NacosRuntimeConfigService service =
            new NacosRuntimeConfigService(props(), applier, null, invalidator);
        CustomerWorkRuntimeConfig active = new CustomerWorkRuntimeConfig();
        active.setRevision("rev-ok");
        assertTrue(service.applyConfig(runtimeJson(active)));
        String activeHash = active.getContentHash();
        clearInvocations(invalidator, applier);

        CustomerWorkRuntimeConfig rejected = routingConfig(
            adminEncrypt("sk-primary", KEY), "corrupted-route-cipher");
        rejected.setRevision("rev-rejected");
        rejected.setContentHash("route-hash-rejected");

        assertFalse(service.applyConfig(mapper.writeValueAsString(rejected)));

        verify(invalidator, never()).beginTransition(anyString());
        verify(applier, never()).apply(any(), any(), any());
        verify(applier, never()).apply(any(), any(), any(), any(), any());
        assertEquals("rev-ok", service.activeRevision());
        assertEquals(activeHash, service.activeContentHash());
    }

    @Test
    void experimentConfig_shouldDecryptBothArmsBeforeInvalidationAndFiveArgumentApply() throws Exception {
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        when(applier.apply(any(), any(), any(), any(), any())).thenReturn(true);
        RuntimeConfigCacheInvalidator invalidator = mock(RuntimeConfigCacheInvalidator.class);
        NacosRuntimeConfigService service =
            new NacosRuntimeConfigService(props(), applier, null, invalidator);
        CustomerWorkRuntimeConfig dto = experimentConfig(
            adminEncrypt("sk-control", KEY), adminEncrypt("sk-treatment", KEY));
        dto.setRevision("experiment-rev-1");

        assertTrue(service.applyConfig(runtimeJson(dto)));

        InOrder order = inOrder(invalidator, applier);
        order.verify(invalidator).beginTransition(anyString());
        ArgumentCaptor<Map<Long, String>> routingKeys = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<Long, String>> experimentKeys = ArgumentCaptor.forClass(Map.class);
        order.verify(applier).apply(any(), any(), any(), routingKeys.capture(), experimentKeys.capture());
        assertTrue(routingKeys.getValue().isEmpty());
        assertEquals(Map.of(11L, "sk-control", 12L, "sk-treatment"), experimentKeys.getValue());
        assertEquals("experiment-rev-1", service.activeRevision());
        assertEquals(dto.getContentHash(), service.activeContentHash());
    }

    @Test
    void treatmentDecryptFailure_shouldNotInvalidateSwitchOrAdvanceIdentity() throws Exception {
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        when(applier.apply(any(), any(), any())).thenReturn(true);
        RuntimeConfigCacheInvalidator invalidator = mock(RuntimeConfigCacheInvalidator.class);
        NacosRuntimeConfigService service =
            new NacosRuntimeConfigService(props(), applier, null, invalidator);
        CustomerWorkRuntimeConfig active = new CustomerWorkRuntimeConfig();
        active.setRevision("rev-ok");
        assertTrue(service.applyConfig(runtimeJson(active)));
        String activeHash = active.getContentHash();
        clearInvocations(invalidator, applier);
        CustomerWorkRuntimeConfig rejected = experimentConfig(
            adminEncrypt("sk-control", KEY), "corrupted-treatment-cipher");
        rejected.setRevision("rev-rejected");
        rejected.setContentHash("experiment-hash-rejected");

        assertFalse(service.applyConfig(mapper.writeValueAsString(rejected)));

        verify(invalidator, never()).beginTransition(anyString());
        verify(applier, never()).apply(any(), any(), any());
        verify(applier, never()).apply(any(), any(), any(), any(), any());
        assertEquals("rev-ok", service.activeRevision());
        assertEquals(activeHash, service.activeContentHash());
    }

    @Test
    void badJsonDoesNotApply() {
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        RuntimeConfigCacheInvalidator invalidator = mock(RuntimeConfigCacheInvalidator.class);
        NacosRuntimeConfigService service =
            new NacosRuntimeConfigService(props(), applier, null, invalidator);
        assertFalse(service.applyConfig("{ this is not valid json"));
        verify(applier, never()).apply(any(), any(), any());
        verify(invalidator, never()).beginTransition(anyString());
    }

    @Test
    void decryptFailureDoesNotApply() throws Exception {
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        RuntimeConfigCacheInvalidator invalidator = mock(RuntimeConfigCacheInvalidator.class);
        NacosRuntimeConfigService service =
            new NacosRuntimeConfigService(props(), applier, null, invalidator);

        CustomerWorkRuntimeConfig dto = new CustomerWorkRuntimeConfig();
        dto.getModel().setProvider("openai");
        dto.getModel().setName("gpt-4o");
        dto.getModel().setApiKeyCipher("corrupted-cipher-not-base64");
        String json = mapper.writeValueAsString(dto);

        assertFalse(service.applyConfig(json));
        verify(applier, never()).apply(any(), any(), any());
        verify(invalidator, never()).beginTransition(anyString());
    }

    @Test
    void missingMalformedAndMismatchedHash_shouldRejectWithoutInvalidationOrApply() throws Exception {
        CustomerWorkProperties properties = props();
        properties.getNacos().setRuntimeConfigAckUrl("http://admin.internal/api/open/runtime-config/acks");
        properties.getNacos().setRuntimeConfigInstanceId("pod-hash-check");
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        RuntimeConfigCacheInvalidator invalidator = mock(RuntimeConfigCacheInvalidator.class);
        OutboxService outbox = mock(OutboxService.class);
        NacosRuntimeConfigService service =
            new NacosRuntimeConfigService(properties, applier, outbox, invalidator);

        CustomerWorkRuntimeConfig missing = new CustomerWorkRuntimeConfig();
        missing.setRevision("rev-missing");
        String missingJson = mapper.writeValueAsString(missing);

        CustomerWorkRuntimeConfig malformed = new CustomerWorkRuntimeConfig();
        malformed.setRevision("rev-malformed");
        malformed.setContentHash("not-sha-256");
        String malformedJson = mapper.writeValueAsString(malformed);

        CustomerWorkRuntimeConfig tampered = new CustomerWorkRuntimeConfig();
        tampered.setRevision("rev-tampered");
        tampered.setSystemPrompt("original prompt");
        runtimeJson(tampered);
        tampered.setSystemPrompt("tampered prompt");
        String tamperedJsonWithOldHash = mapper.writeValueAsString(tampered);

        assertFalse(service.applyConfig(missingJson));
        assertFalse(service.applyConfig(malformedJson));
        assertFalse(service.applyConfig(tamperedJsonWithOldHash));

        verify(invalidator, never()).beginTransition(anyString());
        verify(applier, never()).apply(any(), any(), any());
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(outbox, times(3)).publish(
            eq(RuntimeConfigAckOutboxHandler.TYPE), any(), payloads.capture());
        for (String payload : payloads.getAllValues()) {
            assertEquals("REJECTED", mapper.readValue(payload, RuntimeConfigAck.class).status());
        }
        assertEquals("", service.activeRevision());
        assertEquals("", service.activeContentHash());
    }

    @Test
    void deliveryMetadata_shouldNotChangeSharedContentHashAndMustBeRestored() {
        CustomerWorkRuntimeConfig dto = new CustomerWorkRuntimeConfig();
        dto.setSystemPrompt("stable prompt");
        String expected = RuntimeConfigContentHasher.compute(dto, mapper);
        dto.setPublishedAt("2026-08-22T10:00:00");
        dto.setRevision("revision-2");
        dto.setContentHash(expected);

        assertEquals(expected, RuntimeConfigContentHasher.compute(dto, mapper));
        assertEquals("2026-08-22T10:00:00", dto.getPublishedAt());
        assertEquals("revision-2", dto.getRevision());
        assertEquals(expected, dto.getContentHash());
        assertTrue(RuntimeConfigContentHasher.isValidFormat(expected));
    }

    @Test
    void blankConfigSkipped() {
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        NacosRuntimeConfigService service = new NacosRuntimeConfigService(props(), applier);
        assertFalse(service.applyConfig("   "));
        verify(applier, never()).apply(any(), any(), any());
    }

    @Test
    void appliedConfigEnqueuesDurableAck() throws Exception {
        CustomerWorkProperties properties = props();
        properties.getNacos().setRuntimeConfigAckUrl("http://admin.internal/api/open/runtime-config/acks");
        properties.getNacos().setRuntimeConfigInstanceId("pod-1");
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        when(applier.apply(any(), any(), any())).thenReturn(true);
        OutboxService outbox = mock(OutboxService.class);
        NacosRuntimeConfigService service = new NacosRuntimeConfigService(properties, applier, outbox);

        CustomerWorkRuntimeConfig dto = new CustomerWorkRuntimeConfig();
        dto.setRevision("rev-1");
        String json = runtimeJson(dto);
        String expectedHash = dto.getContentHash();

        assertTrue(service.applyConfig(json));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outbox).publish(eq(RuntimeConfigAckOutboxHandler.TYPE), eq("rev-1"), payload.capture());
        RuntimeConfigAck ack = mapper.readValue(payload.getValue(), RuntimeConfigAck.class);
        assertEquals("APPLIED", ack.status());
        assertEquals("pod-1", ack.instanceId());
        assertEquals(expectedHash, ack.contentHash());
        assertEquals("rev-1", service.activeRevision(), "仅真正应用成功的修订进入调用谱系");
        assertEquals(expectedHash, service.activeContentHash());
    }

    @Test
    void rejectedConfig_shouldKeepLastAppliedIdentity() throws Exception {
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        when(applier.apply(any(), any(), any())).thenReturn(true, false);
        NacosRuntimeConfigService service = new NacosRuntimeConfigService(props(), applier);

        CustomerWorkRuntimeConfig applied = new CustomerWorkRuntimeConfig();
        applied.setRevision("rev-ok");
        CustomerWorkRuntimeConfig rejected = new CustomerWorkRuntimeConfig();
        rejected.setRevision("rev-bad");
        rejected.setSystemPrompt("changed prompt");
        String appliedJson = runtimeJson(applied);
        String rejectedJson = runtimeJson(rejected);

        assertTrue(service.applyConfig(appliedJson));
        assertFalse(service.applyConfig(rejectedJson));
        assertEquals("rev-ok", service.activeRevision());
        assertEquals(applied.getContentHash(), service.activeContentHash());
    }

    @Test
    void failedInitialSubscriptionCanRecoverWithoutRestart() throws Exception {
        CustomerWorkProperties properties = props();
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        com.alibaba.nacos.api.config.ConfigService failedConfigService =
            mock(com.alibaba.nacos.api.config.ConfigService.class);
        when(failedConfigService.getConfig(any(), any(), anyLong()))
            .thenThrow(new IllegalStateException("nacos unavailable"));
        com.alibaba.nacos.api.config.ConfigService configService =
            mock(com.alibaba.nacos.api.config.ConfigService.class);
        when(configService.getConfig(any(), any(), anyLong())).thenReturn(null);
        AtomicInteger attempts = new AtomicInteger();
        NacosRuntimeConfigService service = new NacosRuntimeConfigService(
            properties, applier, null, ignored -> {
                if (attempts.incrementAndGet() == 1) {
                    return failedConfigService;
                }
                return configService;
            });

        assertFalse(service.attemptSubscription());
        assertTrue(service.attemptSubscription());
        assertEquals(2, attempts.get());
        verify(failedConfigService).shutDown();
        verify(configService).addListener(eq(properties.getNacos().getRuntimeConfigDataId()),
            eq(properties.getNacos().getGroup()), any());
    }

    @Test
    void defaultTenantAlias_shouldSubscribeCanonicalDataId() throws Exception {
        CustomerWorkProperties properties = props();
        properties.getNacos().setTenantCode(" DEFAULT ");
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        com.alibaba.nacos.api.config.ConfigService configService =
            mock(com.alibaba.nacos.api.config.ConfigService.class);
        when(configService.getConfig(any(), any(), anyLong())).thenReturn(null);
        NacosRuntimeConfigService service = new NacosRuntimeConfigService(properties, applier);

        service.bind(configService);

        String tenantDataId = properties.getNacos().getRuntimeConfigDataId() + "-tenant-default";
        verify(configService).getConfig(eq(tenantDataId), eq(properties.getNacos().getGroup()), anyLong());
        verify(configService).addListener(eq(tenantDataId), eq(properties.getNacos().getGroup()), any());
        verify(configService, never()).getConfig(eq(properties.getNacos().getRuntimeConfigDataId()),
            any(), anyLong());
        verify(configService, never()).addListener(eq(properties.getNacos().getRuntimeConfigDataId()),
            any(), any());
    }

    @Test
    void tenantConfigMissing_shouldNotReadMainOrLegacyDataId() throws Exception {
        CustomerWorkProperties properties = props();
        properties.getNacos().setTenantCode("__platform__");
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        com.alibaba.nacos.api.config.ConfigService configService =
            mock(com.alibaba.nacos.api.config.ConfigService.class);
        String base = properties.getNacos().getRuntimeConfigDataId();
        String canonical = base + "-tenant-default";
        String legacy = base + "-tenant-__platform__";
        when(configService.getConfig(eq(canonical), any(), anyLong())).thenReturn(null);
        NacosRuntimeConfigService service = new NacosRuntimeConfigService(properties, applier);

        service.bind(configService);

        verify(configService).getConfig(eq(canonical), eq(properties.getNacos().getGroup()), anyLong());
        verify(configService, never()).getConfig(eq(base), any(), anyLong());
        verify(configService, never()).getConfig(eq(legacy), any(), anyLong());
        verify(applier, never()).apply(any(), any(), any());
    }

    @Test
    void tenantConfigRemoval_shouldKeepLastSafeConfigAndNeverReadMain() throws Exception {
        CustomerWorkProperties properties = props();
        properties.getNacos().setTenantCode("acme");
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        when(applier.apply(any(), any(), any())).thenReturn(true);
        com.alibaba.nacos.api.config.ConfigService configService =
            mock(com.alibaba.nacos.api.config.ConfigService.class);
        String mainDataId = properties.getNacos().getRuntimeConfigDataId();
        String tenantDataId = mainDataId + "-tenant-acme";
        CustomerWorkRuntimeConfig active = new CustomerWorkRuntimeConfig();
        active.setRevision("tenant-rev-1");
        when(configService.getConfig(eq(tenantDataId), any(), anyLong())).thenReturn(runtimeJson(active));
        ArgumentCaptor<Listener> listener = ArgumentCaptor.forClass(Listener.class);
        NacosRuntimeConfigService service = new NacosRuntimeConfigService(properties, applier);

        service.bind(configService);
        verify(configService).addListener(eq(tenantDataId), any(), listener.capture());
        listener.getValue().receiveConfigInfo("");

        assertEquals("tenant-rev-1", service.activeRevision());
        verify(applier, times(1)).apply(any(), any(), any());
        verify(configService, never()).getConfig(eq(mainDataId), any(), anyLong());
        verify(configService, never()).addListener(eq(mainDataId), any(), any());
    }

    @Test
    void multiTenantModeWithoutTenantCode_shouldFailBeforeNacosRead() {
        CustomerWorkProperties properties = props();
        properties.getTenant().setEnabled(true);
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        com.alibaba.nacos.api.config.ConfigService configService =
            mock(com.alibaba.nacos.api.config.ConfigService.class);
        NacosRuntimeConfigService service = new NacosRuntimeConfigService(properties, applier);

        assertThrows(IllegalStateException.class, () -> service.bind(configService));

        verifyNoInteractions(configService, applier);
    }

    @Test
    void singleTenantMode_shouldOnlyUseMainDataId() throws Exception {
        CustomerWorkProperties properties = props();
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        com.alibaba.nacos.api.config.ConfigService configService =
            mock(com.alibaba.nacos.api.config.ConfigService.class);
        String mainDataId = properties.getNacos().getRuntimeConfigDataId();
        when(configService.getConfig(eq(mainDataId), any(), anyLong())).thenReturn(null);
        NacosRuntimeConfigService service = new NacosRuntimeConfigService(properties, applier);

        service.bind(configService);

        verify(configService).getConfig(eq(mainDataId), any(), anyLong());
        verify(configService).addListener(eq(mainDataId), any(), any());
    }

    private CustomerWorkRuntimeConfig routingConfig(String firstCipher, String secondCipher) {
        CustomerWorkRuntimeConfig.RoutingDeployment first =
            new CustomerWorkRuntimeConfig.RoutingDeployment();
        first.setDeploymentId(10L);
        first.setProvider("openai");
        first.setName("gpt-4o");
        first.setApiKeyCipher(firstCipher);
        CustomerWorkRuntimeConfig.RoutingDeployment second =
            new CustomerWorkRuntimeConfig.RoutingDeployment();
        second.setDeploymentId(20L);
        second.setProvider("dashscope");
        second.setName("qwen-max");
        second.setApiKeyCipher(secondCipher);
        CustomerWorkRuntimeConfig.RoutingPolicy policy = new CustomerWorkRuntimeConfig.RoutingPolicy();
        policy.setPolicyId(1L);
        policy.setVersionId(2L);
        policy.setDeployments(List.of(first, second));
        CustomerWorkRuntimeConfig dto = new CustomerWorkRuntimeConfig();
        dto.setSchemaVersion(2);
        dto.setRoutingPolicy(policy);
        return dto;
    }

    private CustomerWorkRuntimeConfig experimentConfig(String controlCipher, String treatmentCipher) {
        CustomerWorkRuntimeConfig.ExperimentArm control = new CustomerWorkRuntimeConfig.ExperimentArm();
        control.setArm("CONTROL");
        control.setDeploymentId(11L);
        control.setProvider("openai");
        control.setName("gpt-control");
        control.setApiKeyCipher(controlCipher);
        CustomerWorkRuntimeConfig.ExperimentArm treatment = new CustomerWorkRuntimeConfig.ExperimentArm();
        treatment.setArm("TREATMENT");
        treatment.setDeploymentId(12L);
        treatment.setProvider("dashscope");
        treatment.setName("qwen-treatment");
        treatment.setApiKeyCipher(treatmentCipher);
        CustomerWorkRuntimeConfig.OnlineExperiment experiment =
            new CustomerWorkRuntimeConfig.OnlineExperiment();
        experiment.setExperimentId(7L);
        experiment.setRevision(3);
        experiment.setAssignmentSalt("salt-123");
        experiment.setTreatmentBps(5000);
        experiment.setExpiresAtEpochMs(System.currentTimeMillis() + 60000);
        experiment.setControl(control);
        experiment.setTreatment(treatment);
        CustomerWorkRuntimeConfig dto = new CustomerWorkRuntimeConfig();
        dto.setSchemaVersion(2);
        dto.setOnlineExperiment(experiment);
        return dto;
    }

    private String runtimeJson(CustomerWorkRuntimeConfig dto) throws Exception {
        dto.setContentHash(RuntimeConfigContentHasher.compute(dto, mapper));
        return mapper.writeValueAsString(dto);
    }
}
