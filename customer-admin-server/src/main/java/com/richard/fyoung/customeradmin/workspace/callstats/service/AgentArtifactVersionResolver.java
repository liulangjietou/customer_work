package com.richard.fyoung.customeradmin.workspace.callstats.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentMcp;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentSkill;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentSkillMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiAgentKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersion;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiAgentKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseVersionMapper;
import com.richard.fyoung.customeradmin.aiconfig.mcp.entity.AiMcp;
import com.richard.fyoung.customeradmin.aiconfig.mcp.mapper.AiMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.skill.entity.AiSkillVersion;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillVersionMapper;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.entity.AiAgentSystemTool;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.entity.AiSystemTool;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.mapper.AiAgentSystemToolMapper;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.mapper.AiSystemToolMapper;
import com.richard.fyoung.customerwork.capability.eval.EvalFingerprint;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 按 Agent 绑定关系冻结知识库与全部工具制品指纹；凭据内容始终排除。 */
@Component
public class AgentArtifactVersionResolver {

    private final AiAgentKnowledgeBaseMapper knowledgeBindingMapper;
    private final AiKnowledgeBaseVersionMapper knowledgeVersionMapper;
    private final AiAgentSkillMapper skillBindingMapper;
    private final AiSkillVersionMapper skillVersionMapper;
    private final AiAgentMcpMapper mcpBindingMapper;
    private final AiMcpMapper mcpMapper;
    private final AiAgentSystemToolMapper systemToolBindingMapper;
    private final AiSystemToolMapper systemToolMapper;

    public AgentArtifactVersionResolver(AiAgentKnowledgeBaseMapper knowledgeBindingMapper,
                                        AiKnowledgeBaseVersionMapper knowledgeVersionMapper,
                                        AiAgentSkillMapper skillBindingMapper,
                                        AiSkillVersionMapper skillVersionMapper,
                                        AiAgentMcpMapper mcpBindingMapper,
                                        AiMcpMapper mcpMapper,
                                        AiAgentSystemToolMapper systemToolBindingMapper,
                                        AiSystemToolMapper systemToolMapper) {
        this.knowledgeBindingMapper = knowledgeBindingMapper;
        this.knowledgeVersionMapper = knowledgeVersionMapper;
        this.skillBindingMapper = skillBindingMapper;
        this.skillVersionMapper = skillVersionMapper;
        this.mcpBindingMapper = mcpBindingMapper;
        this.mcpMapper = mcpMapper;
        this.systemToolBindingMapper = systemToolBindingMapper;
        this.systemToolMapper = systemToolMapper;
    }

    public ArtifactVersions resolve(Long agentId) {
        Objects.requireNonNull(agentId, "agentId");
        return new ArtifactVersions(knowledgeVersion(agentId), toolVersion(agentId));
    }

    private String knowledgeVersion(Long agentId) {
        List<AiAgentKnowledgeBase> bindings = knowledgeBindingMapper.selectList(
            new LambdaQueryWrapper<AiAgentKnowledgeBase>()
                .eq(AiAgentKnowledgeBase::getAgentId, agentId));
        List<Long> versionIds = bindings.stream().map(AiAgentKnowledgeBase::getKnowledgeBaseVersionId)
            .filter(Objects::nonNull).distinct().toList();
        Map<Long, AiKnowledgeBaseVersion> versions = versionIds.isEmpty() ? Map.of()
            : knowledgeVersionMapper.selectBatchIds(versionIds).stream()
                .collect(Collectors.toMap(AiKnowledgeBaseVersion::getId, Function.identity()));
        StringBuilder canonical = new StringBuilder();
        bindings.stream().sorted(Comparator.comparing(AiAgentKnowledgeBase::getKnowledgeBaseId))
            .forEach(binding -> {
                AiKnowledgeBaseVersion version = versions.get(binding.getKnowledgeBaseVersionId());
                canonical.append(binding.getKnowledgeBaseId()).append('|')
                    .append(binding.getKnowledgeBaseVersionId()).append('|')
                    .append(version == null ? "missing" : version.getVersionNo()).append('|')
                    .append(version == null ? "missing" : version.getSnapshotHash()).append('|')
                    .append(version == null ? "missing" : version.getQualityStatus()).append('\n');
            });
        return EvalFingerprint.of("admin-kb-bindings-v1", canonical);
    }

    private String toolVersion(Long agentId) {
        StringBuilder canonical = new StringBuilder();
        appendSkills(canonical, agentId);
        appendMcps(canonical, agentId);
        appendSystemTools(canonical, agentId);
        return EvalFingerprint.of("admin-tool-bindings-v1", canonical);
    }

    private void appendSkills(StringBuilder canonical, Long agentId) {
        List<AiAgentSkill> bindings = skillBindingMapper.selectList(
            new LambdaQueryWrapper<AiAgentSkill>().eq(AiAgentSkill::getAgentId, agentId));
        List<Long> versionIds = bindings.stream().map(AiAgentSkill::getSkillVersionId)
            .filter(Objects::nonNull).distinct().toList();
        Map<Long, AiSkillVersion> versions = versionIds.isEmpty() ? Map.of()
            : skillVersionMapper.selectBatchIds(versionIds).stream()
                .collect(Collectors.toMap(AiSkillVersion::getId, Function.identity()));
        bindings.stream().sorted(Comparator.comparing(AiAgentSkill::getSkillId)).forEach(binding -> {
            AiSkillVersion version = versions.get(binding.getSkillVersionId());
            canonical.append("skill|").append(binding.getSkillId()).append('|')
                .append(binding.getSkillVersionId()).append('|')
                .append(version == null ? "missing" : version.getContentHash()).append('\n');
        });
    }

    private void appendMcps(StringBuilder canonical, Long agentId) {
        List<AiAgentMcp> bindings = mcpBindingMapper.selectList(
            new LambdaQueryWrapper<AiAgentMcp>().eq(AiAgentMcp::getAgentId, agentId));
        List<Long> ids = bindings.stream().map(AiAgentMcp::getMcpId).distinct().toList();
        Map<Long, AiMcp> mcps = ids.isEmpty() ? Map.of() : mcpMapper.selectBatchIds(ids).stream()
            .collect(Collectors.toMap(AiMcp::getId, Function.identity()));
        bindings.stream().sorted(Comparator.comparing(AiAgentMcp::getMcpId)).forEach(binding -> {
            AiMcp mcp = mcps.get(binding.getMcpId());
            canonical.append("mcp|").append(binding.getMcpId()).append('|')
                .append(mcp == null ? "missing" : mcp.getMcpType()).append('|')
                .append(mcp == null ? "missing" : mcp.getConfig()).append('|')
                // 只纳入 SecretRef 身份，不读取或散列密钥材料。
                .append(mcp == null ? "missing" : mcp.getSecretRefId()).append('|')
                .append(mcp == null ? "missing" : mcp.getAllowedSubjectTypes()).append('|')
                .append(mcp == null ? "missing" : mcp.getStatus()).append('\n');
        });
    }

    private void appendSystemTools(StringBuilder canonical, Long agentId) {
        List<AiAgentSystemTool> bindings = systemToolBindingMapper.selectList(
            new LambdaQueryWrapper<AiAgentSystemTool>().eq(AiAgentSystemTool::getAgentId, agentId));
        List<Long> ids = bindings.stream().map(AiAgentSystemTool::getSystemToolId).distinct().toList();
        Map<Long, AiSystemTool> tools = ids.isEmpty() ? Map.of()
            : systemToolMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(AiSystemTool::getId, Function.identity()));
        bindings.stream().sorted(Comparator.comparing(AiAgentSystemTool::getSystemToolId))
            .forEach(binding -> {
                AiSystemTool tool = tools.get(binding.getSystemToolId());
                canonical.append("system|").append(binding.getSystemToolId()).append('|')
                    .append(tool == null ? "missing" : tool.getToolCode()).append('|')
                    .append(tool == null ? "missing" : tool.getEnabled()).append('\n');
            });
    }

    public record ArtifactVersions(String knowledgeBaseVersion, String toolVersion) {
    }
}
