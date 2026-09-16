package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.client.KnowledgeSearchClient;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersion;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.runtime.ManagedKnowledgeSearchService;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeBaseVersionService;
import com.richard.fyoung.customeradmin.aiconfig.skill.entity.AiSkillVersion;
import com.richard.fyoung.customeradmin.aiconfig.skill.entity.AiSkillVersionFile;
import com.richard.fyoung.customeradmin.aiconfig.skill.service.SkillVersionService;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeNode;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeSearchResult;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 试用自己的冻结清单与调用身份必须完整传递到知识 ACL，资源变化或局部故障不能静默成功。 */
class AgentDraftTrialResourcesTest {
    private final KnowledgeBaseVersionService knowledge = mock(KnowledgeBaseVersionService.class);
    private final ManagedKnowledgeSearchService managed = mock(ManagedKnowledgeSearchService.class);
    private final KnowledgeSearchClient external = mock(KnowledgeSearchClient.class);
    private final AesGcmCryptoUtil crypto = mock(AesGcmCryptoUtil.class);
    private final SkillVersionService skills = mock(SkillVersionService.class);
    private final AgentDraftTrialResources resources = new AgentDraftTrialResources(knowledge, managed, external, crypto, skills);
    private final AgentInvocationIdentity identity = new AgentInvocationIdentity("Tenant-A", QuotaSubjectType.ADMIN_USER,
        "7", true).forInvocation(AgentInvocationIdentity.CHANNEL_ADMIN, "trial-1", "service");
    private final FrozenAgentDraft draft = new FrozenAgentDraft(null, null, null, List.of(), null,
        List.of(new FrozenAgentDraft.Resource(2L, 3L, "refund", "skill-hash")),
        List.of(new FrozenAgentDraft.Resource(4L, 5L, "帮助中心", "kb-hash")), List.of(), List.of());
    private final AiKnowledgeBaseVersion kb = new AiKnowledgeBaseVersion();
    private final AiSkillVersion skill = new AiSkillVersion();

    @BeforeEach
    void setup() {
        kb.setId(5L); kb.setKnowledgeBaseId(4L); kb.setTenantId("Tenant-A"); kb.setSnapshotHash("kb-hash");
        kb.setDocumentCount(1); kb.setApiKey("cipher-test-only");
        skill.setId(3L); skill.setSkillId(2L); skill.setTenantId("Tenant-A"); skill.setContentHash("skill-hash");
        skill.setContent("售后规则正文");
        when(knowledge.requireVersion(4L, 5L)).thenReturn(kb);
        when(skills.requireVersion(2L, 3L)).thenReturn(skill);
    }

    @Test
    void managedSearchReceivesTheExactFrozenVersionAndTrustedAdminIdentity() {
        var node = new KnowledgeNode("帮助中心", "已授权片段", BigDecimal.ONE, "doc", "chunk");
        when(managed.search("帮助中心", kb, "退款", identity)).thenReturn(List.of(node));
        assertEquals(List.of(node), resources.search(draft, "退款", identity));
        verify(managed).search("帮助中心", kb, "退款", identity);
        verifyNoInteractions(external, crypto);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant-a", "Tenant-B"})
    void foreignOrCaseVariantKnowledgeTenantIsRejectedBeforeAnySearchOrSecretRead(String tenant) {
        kb.setTenantId(tenant);
        assertThrows(BizException.class, () -> resources.search(draft, "退款", identity));
        verifyNoInteractions(managed, external, crypto);
    }

    @Test
    void knowledgeHashDriftIsRejectedBeforePlaintextCredentialsAreResolved() {
        kb.setSnapshotHash("changed");
        assertThrows(BizException.class, () -> resources.search(draft, "退款", identity));
        verifyNoInteractions(managed, external, crypto);
    }

    @Test
    void incompleteExternalKnowledgeResultCannotMasqueradeAsAnEmptySuccessfulRetrieval() {
        kb.setDocumentCount(0); kb.setBaseUrl("https://kb.example"); kb.setAppId("frozen-app");
        when(crypto.decrypt("cipher-test-only")).thenReturn("plain-test-only");
        when(external.searchAllResult(any(), eq("退款"))).thenReturn(new KnowledgeSearchResult(List.of(), false));
        assertThrows(BizException.class, () -> resources.search(draft, "退款", identity));
        verifyNoInteractions(managed);
    }

    @Test
    void frozenSkillBodyIsReadWithoutEnumeratingOrExecutingAttachments() {
        assertEquals("售后规则正文", resources.skill(draft, "refund", "SKILL.md", identity));
        verify(skills, never()).files(any());
    }

    @Test
    void unboundSkillAndChangedSkillHashCannotExposeContent() {
        assertThrows(BizException.class, () -> resources.skill(draft, "another-skill", "SKILL.md", identity));
        verify(skills, never()).requireVersion(any(), any());
        skill.setContentHash("changed");
        assertThrows(BizException.class, () -> resources.skill(draft, "refund", "SKILL.md", identity));
        verify(skills, never()).files(any());
    }

    @Test
    void fileSelectionRequiresExactFrozenPathAndTenant() {
        when(skills.files(3L)).thenReturn(List.of(file("guide.md", "Tenant-A", "说明".getBytes(StandardCharsets.UTF_8)),
            file("secret.md", "tenant-a", "其它租户".getBytes(StandardCharsets.UTF_8))));
        assertEquals("说明", resources.skill(draft, "refund", "guide.md", identity));
        assertThrows(BizException.class, () -> resources.skill(draft, "refund", "../guide.md", identity));
        assertThrows(BizException.class, () -> resources.skill(draft, "refund", "secret.md", identity));
    }

    @Test
    void binaryAttachmentsFailExplicitlyAndLongTextShowsItsTruncation() {
        when(skills.files(3L)).thenReturn(List.of(file("image.bin", "Tenant-A", new byte[]{(byte) 0xc3, 0x28})));
        assertThrows(BizException.class, () -> resources.skill(draft, "refund", "image.bin", identity));
        skill.setContent("文".repeat(16001));
        var text = resources.skill(draft, "refund", "SKILL.md", identity);
        assertTrue(text.startsWith("文".repeat(16000)));
        assertTrue(text.endsWith("[内容超过试用读取上限，已截断]"));
    }

    private AiSkillVersionFile file(String path, String tenant, byte[] content) {
        var file = new AiSkillVersionFile(); file.setSkillVersionId(3L); file.setFilePath(path);
        file.setTenantId(tenant); file.setContent(content); return file;
    }
}
