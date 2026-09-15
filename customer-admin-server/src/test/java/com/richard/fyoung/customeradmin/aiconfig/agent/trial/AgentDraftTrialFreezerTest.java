package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentDraftVO;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.agent.service.AgentService;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersion;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeBaseVersionService;
import com.richard.fyoung.customeradmin.aiconfig.skill.entity.AiSkill;
import com.richard.fyoung.customeradmin.aiconfig.skill.entity.AiSkillVersion;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillMapper;
import com.richard.fyoung.customeradmin.aiconfig.skill.service.SkillVersionService;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.entity.AiSystemTool;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.mapper.AiSystemToolMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.ops.domain.FrozenKnowledgeModel;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 冻结读取的租户、版本和能力范围边界，不执行正式配置写入。 */
class AgentDraftTrialFreezerTest {
    private final AgentService agents = mock(AgentService.class);
    private final AgentDraftTrialModels models = mock(AgentDraftTrialModels.class);
    private final AiSkillMapper skills = mock(AiSkillMapper.class);
    private final SkillVersionService skillVersions = mock(SkillVersionService.class);
    private final AiKnowledgeBaseMapper knowledge = mock(AiKnowledgeBaseMapper.class);
    private final KnowledgeBaseVersionService knowledgeVersions = mock(KnowledgeBaseVersionService.class);
    private final AiSystemToolMapper tools = mock(AiSystemToolMapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ValidatorFactory validator = Validation.buildDefaultValidatorFactory();
    private final AgentDraftTrialFreezer freezer = new AgentDraftTrialFreezer(validator.getValidator(), agents,
        models, skills, skillVersions, knowledge, knowledgeVersions, tools, json);
    private final AiSkillVersion skillVersion = new AiSkillVersion();
    private final AiKnowledgeBaseVersion knowledgeVersion = new AiKnowledgeBaseVersion();
    private final AgentSaveRequest configuration = new AgentSaveRequest("试用", "trial", 1L, List.of(9L),
        List.of(8L), List.of(2L), List.of(6L), "规则", List.of("chat", "memory"), "icon", 1,
        List.of(), 4, 20, 1, null, null, List.of(4L));
    private final AgentDraftVO draft = new AgentDraftVO("draft", null, null, "试用", 3L, 10L, configuration);

    @BeforeEach
    void setup() {
        TenantContext.set("tenant-a");
        when(models.freeze(configuration, null)).thenReturn(new AgentDraftTrialModels.Selection(
            List.of(new FrozenKnowledgeModel(1L, 2, "openai", "https://model.example", "model", 8192)), null));
        var skill = new AiSkill(); skill.setId(2L); skill.setCurrentVersionId(3L);
        when(skills.selectOne(any())).thenReturn(skill);
        skillVersion.setId(3L); skillVersion.setTenantId("tenant-a"); skillVersion.setSkillId(2L);
        skillVersion.setSkillCode("refund"); skillVersion.setContentHash("skill-v1");
        when(skillVersions.requireVersion(2L, 3L)).thenReturn(skillVersion);
        var kb = new AiKnowledgeBase(); kb.setId(4L); kb.setKbName("帮助中心"); kb.setCurrentVersionId(5L);
        when(knowledge.selectOne(any())).thenReturn(kb);
        knowledgeVersion.setId(5L); knowledgeVersion.setKnowledgeBaseId(4L);
        knowledgeVersion.setTenantId("tenant-a"); knowledgeVersion.setSnapshotHash("knowledge-v1");
        knowledgeVersion.setApiKey("never-persist-secret");
        when(knowledgeVersions.requireVersion(4L, 5L)).thenReturn(knowledgeVersion);
        var tool = new AiSystemTool(); tool.setId(6L); tool.setToolCode("httpclient"); tool.setEnabled(1);
        when(tools.selectById(6L)).thenReturn(tool);
    }

    @AfterEach
    void cleanup() { validator.close(); TenantContext.clear(); }

    @Test
    void snapshotContainsFullConfigurationAndImmutableIdentitiesButNoResourceCredentials() throws Exception {
        var frozen = freezer.freeze(draft);
        assertEquals(configuration, frozen.configuration());
        assertEquals(3L, frozen.skills().get(0).versionId());
        assertEquals(5L, frozen.knowledgeBases().get(0).versionId());
        assertTrue(frozen.restrictions().stream().anyMatch(value -> value.contains("MCP")));
        assertTrue(frozen.restrictions().stream().anyMatch(value -> value.contains("httpclient")));
        assertTrue(frozen.restrictions().stream().anyMatch(value -> value.contains("memory")));
        String encoded = freezer.encode(frozen);
        assertFalse(encoded.contains("never-persist-secret"));
        assertFalse(encoded.contains("apiKey"));
        assertEquals(frozen, json.readValue(encoded, FrozenAgentDraft.class));
        verify(agents).validateConfiguration(configuration, null);
        verify(agents, never()).create(any());
    }

    @Test
    void aDifferentImmutableSkillVersionChangesTheConfigurationFingerprint() {
        String before = freezer.fingerprint(freezer.freeze(draft));
        skillVersion.setContentHash("skill-v2");
        assertNotEquals(before, freezer.fingerprint(freezer.freeze(draft)));
    }

    @Test
    void aCaseVariantTenantVersionIsRejectedEvenWhenTheMapperReturnsIt() {
        skillVersion.setTenantId("Tenant-A");
        assertThrows(BizException.class, () -> freezer.freeze(draft));
    }

    @Test
    void foreignKnowledgeVersionCannotEnterTheTrialSnapshot() {
        knowledgeVersion.setTenantId("tenant-b");
        assertThrows(BizException.class, () -> freezer.freeze(draft));
    }

    @Test
    void incompleteSavedDraftIsRejectedBeforeResourceResolution() throws Exception {
        var node = json.valueToTree(configuration);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("agentName", "");
        var invalid = json.treeToValue(node, AgentSaveRequest.class);
        assertThrows(BizException.class, () -> freezer.freeze(new AgentDraftVO("draft", null, null,
            "未完成", 1L, 0L, invalid)));
        verify(agents, never()).validateConfiguration(any(), any());
    }
}
