package com.richard.fyoung.customeradmin.aiconfig.agent.publication;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.entity.AiChannelBinding;
import com.richard.fyoung.customeradmin.aiconfig.channel.mapper.AiChannelBindingMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.RuntimePublishStatus;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimeConfigAckEntity;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.EvalGateDecision;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimeConfigAckMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimePublishTaskMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.TenantSqlConditions;
import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 聚合配置、发布任务和实例回执；查询不触发评测、重试、发布或连接探测。 */
@Service
public class AgentPublicationCheckService {
    private static final String ACK_APPLIED = "APPLIED";
    private static final String ACK_REJECTED = "REJECTED";
    private final AiAgentMapper agents;
    private final AiChannelBindingMapper bindings;
    private final RuntimePublishTaskMapper tasks;
    private final RuntimeConfigAckMapper acknowledgements;
    private final CustomerWorkConfigPublisher publisher;
    private final ObjectMapper json;

    public AgentPublicationCheckService(AiAgentMapper agents, AiChannelBindingMapper bindings,
        RuntimePublishTaskMapper tasks, RuntimeConfigAckMapper acknowledgements,
        CustomerWorkConfigPublisher publisher, ObjectMapper json) {
        this.agents = agents;
        this.bindings = bindings;
        this.tasks = tasks;
        this.acknowledgements = acknowledgements;
        this.publisher = publisher;
        this.json = json;
    }

    /** 同一只读快照内比较发布实际控制的字段；实例确认另按冻结目标、修订与内容哈希核对。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AgentPublicationCheck check(long agentId) {
        String tenant = TenantContext.require();
        var agent = agents.selectOne(owned(AiAgent.class, tenant).eq("id", agentId));
        if (agent == null) throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "智能体不存在或不可见");
        // 实体尚未映射 tenant_id，但实际表已有该列；兼容模式也必须显式隔离渠道关联。
        var channels = bindings.selectList(owned(AiChannelBinding.class, tenant).eq("agent_id", agentId)
                .orderByAsc("id")).stream()
            .map(binding -> new AgentPublicationCheck.Channel(binding.getChannelCode(), enabled(binding.getStatus())))
            .toList();
        boolean publishingEnabled = publisher.isEnabled();
        var candidateStatus = publishingEnabled ? AgentPublicationCheck.CandidateStatus.READY
            : AgentPublicationCheck.CandidateStatus.PUBLISHING_DISABLED;
        if (publishingEnabled && !enabled(agent.getStatus())) {
            candidateStatus = AgentPublicationCheck.CandidateStatus.AGENT_DISABLED;
        } else if (publishingEnabled && channels.stream().noneMatch(AgentPublicationCheck.Channel::enabled)) {
            candidateStatus = AgentPublicationCheck.CandidateStatus.CHANNEL_MISSING;
        }
        EvalVersionBinding current = null;
        if (candidateStatus == AgentPublicationCheck.CandidateStatus.READY) {
            try {
                current = publisher.previewVersionBinding(agentId);
                if (current == null) candidateStatus = AgentPublicationCheck.CandidateStatus.UNAVAILABLE;
            } catch (BizException | IllegalArgumentException | IllegalStateException unavailable) {
                // 历史发布事实仍可查看；不把异常消息里的连接地址或凭据带给浏览器。
                candidateStatus = AgentPublicationCheck.CandidateStatus.UNAVAILABLE;
            }
        }
        var task = tasks.selectOne(owned(RuntimePublishTask.class, tenant).eq("target_id", agentId)
            .orderByDesc("seq").last("LIMIT 1"));
        var publication = task == null ? null : publication(task, current, tenant);
        boolean confirmed = publication != null && current != null
            && publication.currentMatch() == AgentPublicationCheck.CandidateMatch.MATCH
            && RuntimePublishStatus.APPLIED.name().equals(publication.status())
            && publication.confirmation() == AgentPublicationCheck.Confirmation.CONFIRMED;
        return new AgentPublicationCheck(agentId, agent.getAgentName(), agent.getRuntimeRevision(),
            enabled(agent.getStatus()), publishingEnabled, channels, candidateStatus, current,
            publication, confirmed, System.currentTimeMillis());
    }

    private AgentPublicationCheck.Publication publication(RuntimePublishTask task,
        EvalVersionBinding current, String tenant) {
        var candidate = read(task.getCandidateVersionsJson(), EvalVersionBinding.class);
        var match = current == null ? AgentPublicationCheck.CandidateMatch.UNAVAILABLE
            : candidate == null ? AgentPublicationCheck.CandidateMatch.NOT_PREPARED
            : candidate.matchesCandidate(current) ? AgentPublicationCheck.CandidateMatch.MATCH
            : AgentPublicationCheck.CandidateMatch.CHANGED;
        var targets = task.getAckTargetsJson() == null ? null : strings(task.getAckTargetsJson());
        List<AgentPublicationCheck.Acknowledgement> acks = List.of();
        if (StringUtils.hasText(task.getRevision()) && StringUtils.hasText(task.getContentHash())) {
            acks = acknowledgements.selectList(owned(RuntimeConfigAckEntity.class, tenant)
                    .apply("CAST(revision AS BINARY) = CAST({0} AS BINARY)", task.getRevision())
                    .apply("CAST(content_hash AS BINARY) = CAST({0} AS BINARY)", task.getContentHash())
                    .orderByAsc("instance_id")).stream()
                .filter(ack -> targets == null || targets.contains(ack.getInstanceId()))
                .map(ack -> new AgentPublicationCheck.Acknowledgement(ack.getInstanceId(),
                    ack.getStatus(), ack.getAppliedAtMs())).toList();
        }
        return new AgentPublicationCheck.Publication(task.getId(), task.getPublishIntent(), task.getStatus(),
            task.getRevision(), task.getContentHash(), candidate, match, task.getGateStatus(),
            read(task.getGateDecisionJson(), EvalGateDecision.class), strings(task.getGateEvalRunIdsJson()),
            task.getGateEvaluatedAtMs(), task.getGateOverrideId(), targets, acks, confirmation(targets, acks),
            task.getUpdatedAtMs());
    }

    private AgentPublicationCheck.Confirmation confirmation(List<String> targets,
        List<AgentPublicationCheck.Acknowledgement> acks) {
        if (targets == null) return AgentPublicationCheck.Confirmation.LEGACY_UNVERIFIED;
        if (targets.isEmpty()) return AgentPublicationCheck.Confirmation.NO_TARGETS;
        if (acks.stream().anyMatch(ack -> ACK_REJECTED.equals(ack.status()))) {
            return AgentPublicationCheck.Confirmation.REJECTED;
        }
        long applied = acks.stream().filter(ack -> ACK_APPLIED.equals(ack.status()))
            .map(AgentPublicationCheck.Acknowledgement::instanceId).distinct().count();
        if (applied == targets.stream().distinct().count()) return AgentPublicationCheck.Confirmation.CONFIRMED;
        return applied == 0 ? AgentPublicationCheck.Confirmation.WAITING : AgentPublicationCheck.Confirmation.PARTIAL;
    }

    private boolean enabled(Integer value) { return Objects.equals(value, StatusFlags.ENABLED); }

    private <T> QueryWrapper<T> owned(Class<T> type, String tenant) {
        return new QueryWrapper<T>(type).apply(TenantSqlConditions.EXACT_TENANT, tenant);
    }

    private List<String> strings(String value) {
        var values = read(value, String[].class);
        return values == null ? List.of() : List.of(values);
    }

    private <T> T read(String value, Class<T> type) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException invalid) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "发布记录无法解析，请联系管理员核查");
        }
    }
}
