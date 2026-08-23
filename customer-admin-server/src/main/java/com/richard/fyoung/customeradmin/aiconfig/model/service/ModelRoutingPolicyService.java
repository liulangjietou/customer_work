package com.richard.fyoung.customeradmin.aiconfig.model.service;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelDeploymentLifecycle;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelRoutePolicyStatus;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelRoutePurpose;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelRouteVersionStatus;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRouteCandidateExplanationVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRouteCondition;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRouteConflictVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRouteDryRunRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRouteDryRunVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRoutePolicyCreateRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRoutePolicyVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRouteRuleRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRouteRuleVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRouteValidationVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRouteVersionCreateRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRouteVersionVO;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelRoutePolicy;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelRoutePolicyVersion;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelRouteRule;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelRoutePolicyMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelRoutePolicyVersionMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelRouteRuleMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 显式模型路由策略：不可变版本、冲突检测、认证发布门禁和命中解释。 */
@Service
public class ModelRoutingPolicyService {

    private final AiModelRoutePolicyMapper policyMapper;
    private final AiModelRoutePolicyVersionMapper versionMapper;
    private final AiModelRouteRuleMapper ruleMapper;
    private final ModelRouteRuleValidator validator;
    private final ModelConfigAccess modelConfigAccess;
    private final ModelCertificationService certificationService;
    private final AdminTenantProperties tenantProperties;
    private final CrossTenantAuthority crossTenantAuthority;
    private final ObjectMapper objectMapper;
    private final AiAgentMapper agentMapper;
    private final AgentInstanceCache agentInstanceCache;
    private final CustomerWorkConfigPublisher runtimeConfigPublisher;

    public ModelRoutingPolicyService(AiModelRoutePolicyMapper policyMapper,
                                     AiModelRoutePolicyVersionMapper versionMapper,
                                     AiModelRouteRuleMapper ruleMapper,
                                     ModelRouteRuleValidator validator,
                                     ModelConfigAccess modelConfigAccess,
                                     ModelCertificationService certificationService,
                                     AdminTenantProperties tenantProperties,
                                     CrossTenantAuthority crossTenantAuthority,
                                     ObjectMapper objectMapper,
                                     AiAgentMapper agentMapper,
                                     AgentInstanceCache agentInstanceCache,
                                     CustomerWorkConfigPublisher runtimeConfigPublisher) {
        this.policyMapper = policyMapper;
        this.versionMapper = versionMapper;
        this.ruleMapper = ruleMapper;
        this.validator = validator;
        this.modelConfigAccess = modelConfigAccess;
        this.certificationService = certificationService;
        this.tenantProperties = tenantProperties;
        this.crossTenantAuthority = crossTenantAuthority;
        this.objectMapper = objectMapper;
        this.agentMapper = agentMapper;
        this.agentInstanceCache = agentInstanceCache;
        this.runtimeConfigPublisher = runtimeConfigPublisher;
    }

    public List<ModelRoutePolicyVO> list() {
        List<AiModelRoutePolicy> policies = policyMapper.selectList(
            new QueryWrapper<AiModelRoutePolicy>().orderByDesc("update_time"));
        return policies.stream().map(this::toPolicyVo).toList();
    }

    public ModelRoutePolicyVO get(Long policyId) {
        return toPolicyVo(requirePolicy(policyId));
    }

    public List<ModelRouteVersionVO> versions(Long policyId) {
        AiModelRoutePolicy policy = requirePolicy(policyId);
        return versionMapper.selectList(new QueryWrapper<AiModelRoutePolicyVersion>()
                .eq("policy_id", policy.getId())
                .eq("tenant_id", policy.getTenantId())
                .orderByDesc("version_no"))
            .stream().map(this::toVersionVo).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public ModelRoutePolicyVO create(ModelRoutePolicyCreateRequest request) {
        requireSharedWriteAuthority();
        List<ModelRouteConflictVO> conflicts = validator.validate(request.rules());
        requireValid(conflicts);
        validateDeploymentsVisible(request.rules());
        String tenant = ownerTenant();
        String code = request.policyCode().trim().toLowerCase(Locale.ROOT);
        Long count = policyMapper.selectCount(new QueryWrapper<AiModelRoutePolicy>()
            .eq("tenant_id", tenant).eq("policy_code", code));
        if (count != null && count > 0) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "路由策略编码已存在: " + code);
        }

        AiModelRoutePolicy policy = new AiModelRoutePolicy();
        policy.setTenantId(tenant);
        policy.setPolicyCode(code);
        policy.setPolicyName(request.policyName().trim());
        policy.setDescription(request.description());
        policy.setStatus(ModelRoutePolicyStatus.DRAFT.name());
        policy.setLatestVersionNo(1);
        policyMapper.insert(policy);
        insertVersion(policy, 1, request.changeNote(), request.rules());
        return toPolicyVo(policyMapper.selectById(policy.getId()));
    }

    public ModelRouteValidationVO validate(ModelRouteVersionCreateRequest request) {
        List<ModelRouteConflictVO> conflicts = new ArrayList<>(validator.validate(request.rules()));
        if (conflicts.isEmpty()) {
            try {
                validateDeploymentsVisible(request.rules());
            } catch (BizException e) {
                conflicts.add(new ModelRouteConflictVO("DEPLOYMENT_INVALID", null, null, e.getMessage()));
            }
        }
        return new ModelRouteValidationVO(conflicts.isEmpty(), List.copyOf(conflicts));
    }

    @Transactional(rollbackFor = Exception.class)
    public ModelRouteVersionVO createVersion(Long policyId, ModelRouteVersionCreateRequest request) {
        requireSharedWriteAuthority();
        List<ModelRouteConflictVO> conflicts = validator.validate(request.rules());
        requireValid(conflicts);
        validateDeploymentsVisible(request.rules());
        AiModelRoutePolicy policy = requirePolicyForUpdate(policyId);
        int versionNo = (policy.getLatestVersionNo() == null ? 0 : policy.getLatestVersionNo()) + 1;
        AiModelRoutePolicyVersion version = insertVersion(policy, versionNo, request.changeNote(), request.rules());
        policy.setLatestVersionNo(versionNo);
        policyMapper.updateById(policy);
        return toVersionVo(version);
    }

    @Transactional(rollbackFor = Exception.class)
    public ModelRoutePolicyVO activate(Long policyId, Long versionId) {
        requireSharedWriteAuthority();
        AiModelRoutePolicy policy = requirePolicyForUpdate(policyId);
        AiModelRoutePolicyVersion version = requireVersion(policy, versionId);
        if (!ModelRouteVersionStatus.DRAFT.name().equals(version.getStatus())) {
            throw new BizException(ResultCode.PARAM_INVALID, "只能激活 DRAFT 路由版本");
        }
        List<AiModelRouteRule> rules = rules(version);
        if (rules.isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID, "路由版本没有规则，无法激活");
        }
        for (Long deploymentId : rules.stream().map(AiModelRouteRule::getDeploymentId).distinct().toList()) {
            AiModelConfig deployment = modelConfigAccess.findVisibleAnyStateById(deploymentId);
            if (deployment == null
                || !Integer.valueOf(StatusFlags.ENABLED).equals(deployment.getStatus())
                || !ModelDeploymentLifecycle.ACTIVE.name().equals(deployment.getLifecycleStatus())) {
                throw new BizException(ResultCode.PARAM_INVALID,
                    "路由引用的模型部署不是 ACTIVE: " + deploymentId);
            }
            certificationService.requirePassedCurrent(deployment);
        }

        if (policy.getCurrentVersionId() != null) {
            versionMapper.update(null, new UpdateWrapper<AiModelRoutePolicyVersion>()
                .eq("id", policy.getCurrentVersionId())
                .eq("tenant_id", policy.getTenantId())
                .set("status", ModelRouteVersionStatus.RETIRED.name()));
        }
        LocalDateTime now = LocalDateTime.now();
        versionMapper.update(null, new UpdateWrapper<AiModelRoutePolicyVersion>()
            .eq("id", version.getId())
            .eq("tenant_id", policy.getTenantId())
            .set("status", ModelRouteVersionStatus.ACTIVE.name())
            .set("activated_by", currentUserId())
            .set("activated_at", now));
        policy.setStatus(ModelRoutePolicyStatus.ACTIVE.name());
        policy.setCurrentVersionId(version.getId());
        policy.setCurrentVersionNo(version.getVersionNo());
        policyMapper.updateById(policy);
        refreshBoundAgents(policy.getId());
        return toPolicyVo(policyMapper.selectById(policy.getId()));
    }

    /** 策略激活必须进入真实运行链：清 Admin 实例缓存，并为每个已绑定 Agent 登记可靠发布任务。 */
    private void refreshBoundAgents(Long policyId) {
        List<AiAgent> agents = agentMapper.selectList(new LambdaQueryWrapper<AiAgent>()
            .eq(AiAgent::getModelRoutePolicyId, policyId)
            .eq(AiAgent::getStatus, StatusFlags.ENABLED));
        if (CollectionUtils.isEmpty(agents)) {
            return;
        }
        for (AiAgent agent : agents) {
            agentInstanceCache.evict(agent.getAgentCode());
            runtimeConfigPublisher.publishForAgentId(agent.getId());
        }
    }

    public ModelRouteDryRunVO dryRun(Long policyId, ModelRouteDryRunRequest request) {
        AiModelRoutePolicy policy = requirePolicy(policyId);
        if (policy.getCurrentVersionId() == null
            || !ModelRoutePolicyStatus.ACTIVE.name().equals(policy.getStatus())) {
            throw new BizException(ResultCode.PARAM_INVALID, "路由策略尚无 ACTIVE 版本");
        }
        AiModelRoutePolicyVersion version = requireVersion(policy, policy.getCurrentVersionId());
        List<AiModelRouteRule> allRules = rules(version).stream()
            .sorted(Comparator.comparing(AiModelRouteRule::getPriority).thenComparing(AiModelRouteRule::getId))
            .toList();
        boolean preferFallback = Boolean.TRUE.equals(request.preferFallback());
        List<AiModelRouteRule> candidates = allRules.stream()
            .filter(rule -> preferFallback
                ? ModelRoutePurpose.FALLBACK.name().equals(rule.getPurpose())
                : !ModelRoutePurpose.FALLBACK.name().equals(rule.getPurpose()))
            .toList();
        ModelRouteRuleValidator.ModelRouteDryRunContext context = new ModelRouteRuleValidator.ModelRouteDryRunContext(
            request.agentId(), request.channelCode(), request.inputTokens(), request.requiresTools(),
            request.requiresStructuredOutput(), request.complexity());
        List<ModelRouteCandidateExplanationVO> explanations = new ArrayList<>();
        AiModelRouteRule selected = null;
        for (AiModelRouteRule rule : candidates) {
            ModelRouteRuleValidator.MatchExplanation match = preferFallback
                ? new ModelRouteRuleValidator.MatchExplanation(true, List.of("preferFallback 强制仅选择备用候选"))
                : validator.explain(readCondition(rule.getConditionJson()), context);
            explanations.add(new ModelRouteCandidateExplanationVO(rule.getId(), rule.getPurpose(),
                rule.getDeploymentId(), rule.getPriority(), match.matched(), match.reasons()));
            if (selected == null && match.matched()) {
                selected = rule;
            }
        }
        return dryRunResult(policy, version, selected, explanations, preferFallback);
    }

    private ModelRouteDryRunVO dryRunResult(AiModelRoutePolicy policy,
                                            AiModelRoutePolicyVersion version,
                                            AiModelRouteRule selected,
                                            List<ModelRouteCandidateExplanationVO> explanations,
                                            boolean preferFallback) {
        ModelRouteDryRunVO result = new ModelRouteDryRunVO();
        result.setPolicyId(policy.getId());
        result.setVersionNo(version.getVersionNo());
        result.setContentHash(version.getContentHash());
        result.setCandidates(List.copyOf(explanations));
        if (selected == null) {
            result.setMatched(false);
            result.setFailClosed(true);
            result.setExplanation(preferFallback
                ? "preferFallback 未配置备用部署，按既有 DEGRADE 契约 fail-closed"
                : "没有规则命中，路由 fail-closed");
            return result;
        }
        AiModelConfig deployment = modelConfigAccess.findVisibleById(selected.getDeploymentId());
        if (deployment == null) {
            result.setMatched(false);
            result.setFailClosed(true);
            result.setExplanation("命中部署当前不可运行，路由 fail-closed");
            return result;
        }
        result.setMatched(true);
        result.setFailClosed(false);
        result.setDeploymentId(deployment.getId());
        result.setDeploymentCode(deployment.getDeploymentCode());
        result.setDeploymentName(deployment.getModelName());
        result.setPurpose(selected.getPurpose());
        result.setPriority(selected.getPriority());
        result.setExplanation("命中 priority=" + selected.getPriority() + " 的 " + selected.getPurpose()
            + " 规则：" + selected.getConditionSummary());
        return result;
    }

    private AiModelRoutePolicyVersion insertVersion(AiModelRoutePolicy policy,
                                                    int versionNo,
                                                    String changeNote,
                                                    List<ModelRouteRuleRequest> requests) {
        List<ModelRouteRuleRequest> normalized = normalizeRules(requests);
        AiModelRoutePolicyVersion version = new AiModelRoutePolicyVersion();
        version.setTenantId(policy.getTenantId());
        version.setPolicyId(policy.getId());
        version.setVersionNo(versionNo);
        version.setStatus(ModelRouteVersionStatus.DRAFT.name());
        version.setContentHash(contentHash(normalized));
        version.setChangeNote(changeNote);
        versionMapper.insert(version);
        for (ModelRouteRuleRequest request : normalized) {
            AiModelRouteRule rule = new AiModelRouteRule();
            rule.setTenantId(policy.getTenantId());
            rule.setPolicyVersionId(version.getId());
            rule.setPurpose(request.purpose());
            rule.setDeploymentId(request.deploymentId());
            rule.setPriority(request.priority());
            rule.setConditionJson(writeCondition(request.condition()));
            rule.setConditionSummary(validator.summary(request.condition()));
            ruleMapper.insert(rule);
        }
        return version;
    }

    private List<ModelRouteRuleRequest> normalizeRules(List<ModelRouteRuleRequest> requests) {
        return requests.stream()
            .map(rule -> new ModelRouteRuleRequest(rule.purpose().trim().toUpperCase(Locale.ROOT),
                rule.deploymentId(), rule.priority(), validator.normalize(rule.condition())))
            .sorted(Comparator.comparing(ModelRouteRuleRequest::priority)
                .thenComparing(ModelRouteRuleRequest::purpose)
                .thenComparing(ModelRouteRuleRequest::deploymentId))
            .toList();
    }

    private void validateDeploymentsVisible(List<ModelRouteRuleRequest> rules) {
        for (Long deploymentId : rules.stream().map(ModelRouteRuleRequest::deploymentId).filter(Objects::nonNull)
            .distinct().toList()) {
            if (modelConfigAccess.findVisibleAnyStateById(deploymentId) == null) {
                throw new BizException(ResultCode.PARAM_INVALID,
                    "路由规则引用了不可见或不存在的模型部署: " + deploymentId);
            }
        }
    }

    private void requireValid(List<ModelRouteConflictVO> conflicts) {
        if (!conflicts.isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID, conflicts.get(0).message());
        }
    }

    private AiModelRoutePolicy requirePolicy(Long policyId) {
        AiModelRoutePolicy policy = policyMapper.selectById(policyId);
        if (policy == null || !visible(policy.getTenantId())) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "路由策略不存在: " + policyId);
        }
        return policy;
    }

    private AiModelRoutePolicy requirePolicyForUpdate(Long policyId) {
        AiModelRoutePolicy policy = policyMapper.selectOne(new QueryWrapper<AiModelRoutePolicy>()
            .eq("id", policyId).last("FOR UPDATE"));
        if (policy == null || !visible(policy.getTenantId())) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "路由策略不存在: " + policyId);
        }
        return policy;
    }

    private AiModelRoutePolicyVersion requireVersion(AiModelRoutePolicy policy, Long versionId) {
        AiModelRoutePolicyVersion version = versionMapper.selectOne(new QueryWrapper<AiModelRoutePolicyVersion>()
            .eq("id", versionId).eq("policy_id", policy.getId()).eq("tenant_id", policy.getTenantId()));
        if (version == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "路由策略版本不存在: " + versionId);
        }
        return version;
    }

    private List<AiModelRouteRule> rules(AiModelRoutePolicyVersion version) {
        return ruleMapper.selectList(new QueryWrapper<AiModelRouteRule>()
            .eq("policy_version_id", version.getId()).eq("tenant_id", version.getTenantId())
            .orderByAsc("priority", "id"));
    }

    private ModelRoutePolicyVO toPolicyVo(AiModelRoutePolicy policy) {
        ModelRoutePolicyVO vo = new ModelRoutePolicyVO();
        vo.setId(policy.getId());
        vo.setPolicyCode(policy.getPolicyCode());
        vo.setPolicyName(policy.getPolicyName());
        vo.setDescription(policy.getDescription());
        vo.setStatus(policy.getStatus());
        vo.setCurrentVersionNo(policy.getCurrentVersionNo());
        vo.setLatestVersionNo(policy.getLatestVersionNo());
        vo.setUpdateTime(policy.getUpdateTime());
        if (policy.getCurrentVersionId() != null) {
            AiModelRoutePolicyVersion version = requireVersion(policy, policy.getCurrentVersionId());
            vo.setCurrentVersion(toVersionVo(version));
        }
        return vo;
    }

    private ModelRouteVersionVO toVersionVo(AiModelRoutePolicyVersion version) {
        List<AiModelRouteRule> rules = rules(version);
        Map<Long, AiModelConfig> deployments = rules.stream().map(AiModelRouteRule::getDeploymentId).distinct()
            .map(modelConfigAccess::findVisibleAnyStateById).filter(Objects::nonNull)
            .collect(Collectors.toMap(AiModelConfig::getId, model -> model, (left, right) -> left,
                LinkedHashMap::new));
        ModelRouteVersionVO vo = new ModelRouteVersionVO();
        vo.setId(version.getId());
        vo.setVersionNo(version.getVersionNo());
        vo.setStatus(version.getStatus());
        vo.setContentHash(version.getContentHash());
        vo.setChangeNote(version.getChangeNote());
        vo.setActivatedBy(version.getActivatedBy());
        vo.setActivatedAt(version.getActivatedAt());
        vo.setCreateBy(version.getCreateBy());
        vo.setCreateTime(version.getCreateTime());
        vo.setRules(rules.stream().map(rule -> toRuleVo(rule, deployments.get(rule.getDeploymentId()))).toList());
        return vo;
    }

    private ModelRouteRuleVO toRuleVo(AiModelRouteRule rule, AiModelConfig deployment) {
        ModelRouteRuleVO vo = new ModelRouteRuleVO();
        vo.setId(rule.getId());
        vo.setPurpose(rule.getPurpose());
        vo.setDeploymentId(rule.getDeploymentId());
        vo.setDeploymentCode(deployment == null ? null : deployment.getDeploymentCode());
        vo.setDeploymentName(deployment == null ? null : deployment.getModelName());
        vo.setPriority(rule.getPriority());
        vo.setCondition(readCondition(rule.getConditionJson()));
        vo.setConditionSummary(rule.getConditionSummary());
        return vo;
    }

    private String writeCondition(ModelRouteCondition condition) {
        try {
            return objectMapper.writeValueAsString(validator.normalize(condition));
        } catch (Exception e) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "路由条件序列化失败");
        }
    }

    private ModelRouteCondition readCondition(String json) {
        try {
            return validator.normalize(objectMapper.readValue(json, ModelRouteCondition.class));
        } catch (Exception e) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "路由条件数据不可解析");
        }
    }

    private String contentHash(List<ModelRouteRuleRequest> normalized) {
        try {
            String content = objectMapper.writeValueAsString(normalized);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK missing SHA-256", e);
        } catch (Exception e) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "路由版本摘要生成失败");
        }
    }

    private String ownerTenant() {
        return tenantProperties.isEnabled() ? requireTenant() : TenantContext.DEFAULT;
    }

    private boolean visible(String ownerTenant) {
        return !tenantProperties.isEnabled() || TenantContext.sameTenant(requireTenant(), ownerTenant);
    }

    private String requireTenant() {
        String tenant = TenantSession.effectiveTenant();
        if (!StringUtils.hasText(tenant)) {
            throw new BizException(ResultCode.FORBIDDEN, "缺少租户上下文，无法访问路由策略");
        }
        return tenant;
    }

    private void requireSharedWriteAuthority() {
        if (tenantProperties.isEnabled() && TenantContext.isDefaultTenant(requireTenant())
            && !crossTenantAuthority.hasCurrentUserAuthority()) {
            throw new BizException(ResultCode.FORBIDDEN, "只有控制面角色可以修改 default 路由策略");
        }
    }

    private Long currentUserId() {
        try {
            return StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        } catch (SaTokenException e) {
            return null;
        }
    }
}
