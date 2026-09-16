package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentDraftVO;
import com.richard.fyoung.customeradmin.aiconfig.agent.service.AgentService;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeBaseVersionService;
import com.richard.fyoung.customeradmin.aiconfig.skill.entity.AiSkill;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillMapper;
import com.richard.fyoung.customeradmin.aiconfig.skill.service.SkillVersionService;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.mapper.AiSystemToolMapper;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.TenantSqlConditions;
import com.richard.fyoung.customerwork.capability.eval.EvalFingerprint;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

/** 将可编辑草稿解析为服务器拥有的试用快照；正式配置校验与资源授权不交给浏览器。 */
@Service
public class AgentDraftTrialFreezer {
    public static final String COMPUTE_TOOL = "devtoolbox";
    private static final String POLICY_VERSION = "agent-draft-readonly-trial-v1";
    private final Validator validator;
    private final AgentService agents;
    private final AgentDraftTrialModels models;
    private final AiSkillMapper skills;
    private final SkillVersionService skillVersions;
    private final AiKnowledgeBaseMapper knowledgeBases;
    private final KnowledgeBaseVersionService knowledgeVersions;
    private final AiSystemToolMapper systemTools;
    private final ObjectMapper json;

    public AgentDraftTrialFreezer(Validator validator, AgentService agents, AgentDraftTrialModels models,
        AiSkillMapper skills, SkillVersionService skillVersions, AiKnowledgeBaseMapper knowledgeBases,
        KnowledgeBaseVersionService knowledgeVersions, AiSystemToolMapper systemTools, ObjectMapper json) {
        this.validator = validator;
        this.agents = agents;
        this.models = models;
        this.skills = skills;
        this.skillVersions = skillVersions;
        this.knowledgeBases = knowledgeBases;
        this.knowledgeVersions = knowledgeVersions;
        this.systemTools = systemTools;
        this.json = json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    /** 个人草稿允许不完整保存，但试用只接受通过同一配置校验的完整版本。 */
    public FrozenAgentDraft freeze(AgentDraftVO draft) {
        var configuration = draft.configuration();
        var errors = validator.validate(configuration);
        if (!errors.isEmpty()) throw new BizException(ResultCode.PARAM_INVALID,
            errors.iterator().next().getMessage());
        agents.validateConfiguration(configuration, draft.agentId());
        var selection = models.freeze(configuration, draft.agentId());
        var frozenSkills = ids(configuration.skillIds()).stream().map(this::skill).toList();
        var frozenKnowledge = ids(configuration.knowledgeBaseIds()).stream().map(this::knowledge).toList();
        var frozenTools = ids(configuration.systemToolIds()).stream().map(this::systemTool).toList();
        List<String> restrictions = new ArrayList<>();
        if (!CollectionUtils.isEmpty(configuration.mcpIds())) {
            restrictions.add("MCP 工具不执行，本次不能验证外部工具行为");
        }
        for (var tool : frozenTools) {
            if (!tool.enabled() || !COMPUTE_TOOL.equals(tool.code())) {
                restrictions.add("系统工具 " + tool.code() + " 未进入本次纯计算工具范围");
            }
        }
        if (configuration.capabilities() != null) {
            for (String capability : configuration.capabilities()) {
                if (!"chat".equals(capability)) {
                    restrictions.add("能力 " + capability + " 不在本次独立会话中执行");
                }
            }
        }
        if (configuration.compressTriggerMsgs() != null || configuration.compressKeepMsgs() != null) {
            restrictions.add("长期上下文压缩需要在正式运行环境另行验证");
        }
        if (!frozenSkills.isEmpty()) restrictions.add("Skill 只读取冻结说明和文本附件，不执行脚本");
        return new FrozenAgentDraft(draft.agentId(), draft.baseRevision(), configuration, selection.models(),
            selection.routing(), frozenSkills, frozenKnowledge, frozenTools, restrictions);
    }

    /** 对固定 DTO 稳定序列化；凭据实体不得传给本入口。 */
    public String encode(FrozenAgentDraft snapshot) {
        try {
            return json.writeValueAsString(snapshot);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Agent trial snapshot serialization failed", error);
        }
    }

    /** 指纹覆盖完整草稿、所有资源身份和执行范围版本。 */
    public String fingerprint(FrozenAgentDraft snapshot) {
        return EvalFingerprint.of(POLICY_VERSION, encode(snapshot));
    }

    private FrozenAgentDraft.Resource skill(long id) {
        var resource = skills.selectOne(owned(AiSkill.class, id));
        if (resource == null) throw missing();
        var version = skillVersions.requireVersion(id, resource.getCurrentVersionId());
        requireTenant(version.getTenantId());
        return new FrozenAgentDraft.Resource(id, version.getId(), version.getSkillCode(), version.getContentHash());
    }

    private FrozenAgentDraft.Resource knowledge(long id) {
        var resource = knowledgeBases.selectOne(owned(AiKnowledgeBase.class, id));
        if (resource == null) throw missing();
        var version = knowledgeVersions.requireVersion(id, resource.getCurrentVersionId());
        requireTenant(version.getTenantId());
        return new FrozenAgentDraft.Resource(id, version.getId(), resource.getKbName(), version.getSnapshotHash());
    }

    private FrozenAgentDraft.SystemTool systemTool(long id) {
        // 系统工具是代码内置全局目录，无 tenant_id；绑定权限已由配置服务检查。
        var tool = systemTools.selectById(id);
        if (tool == null) throw missing();
        return new FrozenAgentDraft.SystemTool(id, tool.getToolCode(),
            Integer.valueOf(StatusFlags.ENABLED).equals(tool.getEnabled()));
    }

    private <T> QueryWrapper<T> owned(Class<T> type, long id) {
        return new QueryWrapper<T>(type).eq("id", id)
            .apply(TenantSqlConditions.EXACT_TENANT, TenantContext.require());
    }

    private List<Long> ids(List<Long> ids) {
        return CollectionUtils.isEmpty(ids) ? List.of() : ids;
    }

    private void requireTenant(String tenant) {
        if (!TenantContext.require().equals(tenant)) throw missing();
    }

    private BizException missing() {
        return new BizException(ResultCode.RESOURCE_NOT_FOUND, "试用引用的资源或版本不可见");
    }
}
