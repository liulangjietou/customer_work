package com.richard.fyoung.customeradmin.aiconfig.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentVO;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentBackupModel;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentMcp;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentSkill;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentSubAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentBackupModelMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentSkillMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentSubAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeBaseTestResult;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiAgentKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiAgentKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.mcp.mapper.AiMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelConfigMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelConfigService;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillMapper;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.entity.AiAgentSystemTool;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.mapper.AiAgentSystemToolMapper;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.mapper.AiSystemToolMapper;
import com.richard.fyoung.customeradmin.common.constant.AgentCapabilities;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.menu.service.MenuVersionHolder;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 智能体管理：CRUD + 关联表（MCP/Skill）整体替换式维护 + 启用停用生命周期。
 * 智能体可见性变化（新建/编辑/删除/启停）都会影响动态菜单，统一在此处 {@link MenuVersionHolder#bump()}。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AgentService {


    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private static final Pattern AGENT_CODE_PATTERN = Pattern.compile("^[a-z0-9-]+$");
    private static final Set<String> VALID_CAPABILITIES =
        Set.of(AgentCapabilities.CHAT, AgentCapabilities.VIBECODING, AgentCapabilities.SUBAGENT,
            AgentCapabilities.PLAN, AgentCapabilities.TASKLIST, AgentCapabilities.SKILL_LEARNING,
            AgentCapabilities.DYNAMIC_SUBAGENT, AgentCapabilities.MEMORY);
    /** 保存门禁的连通性测试等待上限（秒）：给 ModelConfigService 内部 10s 硬超时留余量。 */
    private static final long MODEL_TEST_GATE_TIMEOUT_SECONDS = 12;
    /** 主模型连通性门禁失败的日志错误码。 */
    private static final String CODE_MODEL_TEST_FAIL = "AGENT-MODEL-TEST-FAIL";
    /** 启用态（知识库/资源通用）。 */
    // ---- 高级参数取值范围（选填，null 不校验） ----
    private static final int MAX_ITERS_MIN = 1;
    private static final int MAX_ITERS_MAX = 100;
    private static final int TOOL_TIMEOUT_SECONDS_MIN = 1;
    private static final int TOOL_TIMEOUT_SECONDS_MAX = 3600;
    private static final int TOOL_MAX_ATTEMPTS_MIN = 1;
    private static final int TOOL_MAX_ATTEMPTS_MAX = 10;
    private static final int COMPRESS_TRIGGER_MSGS_MIN = 2;
    private static final int COMPRESS_TRIGGER_MSGS_MAX = 1000;
    private static final int COMPRESS_KEEP_MSGS_MIN = 0;
    private static final int COMPRESS_KEEP_MSGS_MAX = 500;

    private final AiAgentMapper agentMapper;
    private final AiAgentMcpMapper agentMcpMapper;
    private final AiAgentSkillMapper agentSkillMapper;
    private final AiAgentBackupModelMapper agentBackupModelMapper;
    private final AiAgentSubAgentMapper agentSubAgentMapper;
    private final AiModelConfigMapper modelConfigMapper;
    private final AiMcpMapper mcpMapper;
    private final AiSkillMapper skillMapper;
    private final AiAgentSystemToolMapper agentSystemToolMapper;
    private final AiSystemToolMapper systemToolMapper;
    private final AiAgentKnowledgeBaseMapper agentKnowledgeBaseMapper;
    private final AiKnowledgeBaseMapper knowledgeBaseMapper;
    private final MenuVersionHolder menuVersionHolder;
    private final AgentInstanceCache agentInstanceCache;
    private final ModelConfigService modelConfigService;
    private final CustomerWorkConfigPublisher runtimeConfigPublisher;

    public AgentService(AiAgentMapper agentMapper, AiAgentMcpMapper agentMcpMapper,
                         AiAgentSkillMapper agentSkillMapper, AiAgentBackupModelMapper agentBackupModelMapper,
                         AiAgentSubAgentMapper agentSubAgentMapper,
                         AiModelConfigMapper modelConfigMapper,
                         AiMcpMapper mcpMapper, AiSkillMapper skillMapper,
                         AiAgentSystemToolMapper agentSystemToolMapper, AiSystemToolMapper systemToolMapper,
                         AiAgentKnowledgeBaseMapper agentKnowledgeBaseMapper,
                         AiKnowledgeBaseMapper knowledgeBaseMapper,
                         MenuVersionHolder menuVersionHolder, AgentInstanceCache agentInstanceCache,
                         ModelConfigService modelConfigService,
                         CustomerWorkConfigPublisher runtimeConfigPublisher) {
        this.agentMapper = agentMapper;
        this.agentMcpMapper = agentMcpMapper;
        this.agentSkillMapper = agentSkillMapper;
        this.agentBackupModelMapper = agentBackupModelMapper;
        this.agentSubAgentMapper = agentSubAgentMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.mcpMapper = mcpMapper;
        this.skillMapper = skillMapper;
        this.agentSystemToolMapper = agentSystemToolMapper;
        this.systemToolMapper = systemToolMapper;
        this.agentKnowledgeBaseMapper = agentKnowledgeBaseMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.menuVersionHolder = menuVersionHolder;
        this.agentInstanceCache = agentInstanceCache;
        this.modelConfigService = modelConfigService;
        this.runtimeConfigPublisher = runtimeConfigPublisher;
    }

    public PageResult<AgentVO> page(PageQuery query) {
        LambdaQueryWrapper<AiAgent> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.like(AiAgent::getAgentName, query.getKeyword());
        }
        if (query.getStatus() != null) {
            wrapper.eq(AiAgent::getStatus, query.getStatus());
        }
        wrapper.orderBy(true, "asc".equalsIgnoreCase(query.getSortOrder()), AiAgent::getCreateTime);

        IPage<AiAgent> page = agentMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return PageResult.of(page.convert(this::toVo));
    }

    public AgentVO get(Long id) {
        return toVo(requireAgent(id));
    }

    /** 返回当前启用中的智能体（动态菜单聚合用，不分页）。 */
    public List<AiAgent> listEnabled() {
        LambdaQueryWrapper<AiAgent> wrapper = new LambdaQueryWrapper<AiAgent>().eq(AiAgent::getStatus, 1);
        return agentMapper.selectList(wrapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public void create(AgentSaveRequest request) {
        validate(request, null);
        // 新建：主模型必须实测连通性通过才允许保存
        assertPrimaryModelConnectivity(request.modelId());
        AiAgent agent = new AiAgent();
        fillFromRequest(agent, request);
        agentMapper.insert(agent);
        replaceRelations(agent.getId(), request.backupModelIds(), request.mcpIds(), request.skillIds(),
            request.systemToolIds(), request.subAgentIds(), request.knowledgeBaseIds());
        menuVersionHolder.bump();
        // 命中渠道绑定则热下发运行时配置到 8080（默认关闭，未启用即跳过）
        runtimeConfigPublisher.publishForAgentId(agent.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, AgentSaveRequest request) {
        AiAgent agent = requireAgent(id);
        String oldAgentCode = agent.getAgentCode();
        Long oldModelId = agent.getModelId();
        validate(request, id);
        // 编辑：仅当主模型变更时才实测连通性
        if (!request.modelId().equals(oldModelId)) {
            assertPrimaryModelConnectivity(request.modelId());
        }
        fillFromRequest(agent, request);
        agentMapper.updateById(agent);
        replaceRelations(id, request.backupModelIds(), request.mcpIds(), request.skillIds(),
            request.systemToolIds(), request.subAgentIds(), request.knowledgeBaseIds());
        menuVersionHolder.bump();
        // agentCode 理论上不应改变，但仍按新旧两个 code 双清，避免缓存键错位残留旧实例
        agentInstanceCache.evict(oldAgentCode);
        agentInstanceCache.evict(request.agentCode());
        // 命中渠道绑定则热下发运行时配置到 8080（默认关闭，未启用即跳过）
        runtimeConfigPublisher.publishForAgentId(id);
    }

    /**
     * 保存门禁：同步实测主模型连通性，非成功即抛业务异常阻止保存。防御只做这一处（后端保存链路），
     * 运行时不再重复校验（运行时靠 FailoverModel 主备容错兜底）。
     */
    private void assertPrimaryModelConnectivity(Long modelId) {
        try {
            ModelTestResult result = modelConfigService.testConnectivity(modelId)
                .get(MODEL_TEST_GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (result.testStatus() != ConnectivityTestStatus.SUCCESS) {
                throw new BizException(ResultCode.MODEL_TEST_FAILED, "主模型连通性测试未通过: " + result.message());
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("primary model connectivity gate failed, code={}, modelId={}", CODE_MODEL_TEST_FAIL, modelId, e);
            throw new BizException(ResultCode.MODEL_TEST_FAILED, "主模型连通性测试执行异常: " + e.getMessage());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        AiAgent agent = requireAgent(id);
        agentMapper.deleteById(id);
        agentMcpMapper.delete(new LambdaQueryWrapper<AiAgentMcp>().eq(AiAgentMcp::getAgentId, id));
        agentSkillMapper.delete(new LambdaQueryWrapper<AiAgentSkill>().eq(AiAgentSkill::getAgentId, id));
        agentSystemToolMapper.delete(new LambdaQueryWrapper<AiAgentSystemTool>().eq(AiAgentSystemTool::getAgentId, id));
        agentBackupModelMapper.delete(new LambdaQueryWrapper<AiAgentBackupModel>().eq(AiAgentBackupModel::getAgentId, id));
        agentSubAgentMapper.delete(new LambdaQueryWrapper<AiAgentSubAgent>().eq(AiAgentSubAgent::getAgentId, id));
        agentKnowledgeBaseMapper.delete(
            new LambdaQueryWrapper<AiAgentKnowledgeBase>().eq(AiAgentKnowledgeBase::getAgentId, id));
        menuVersionHolder.bump();
        agentInstanceCache.evict(agent.getAgentCode());
    }

    /** 启用/停用（生命周期），不改动其余字段。 */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, int status) {
        AiAgent agent = requireAgent(id);
        AiAgent update = new AiAgent();
        update.setId(id);
        update.setStatus(status);
        agentMapper.updateById(update);
        menuVersionHolder.bump();
        agentInstanceCache.evict(agent.getAgentCode());
        // 启停会影响运行时配置有效性，命中绑定则重新下发（启用时下发最新配置；停用后由绑定停用侧处理）
        runtimeConfigPublisher.publishForAgentId(id);
    }

    /**
     * modelId 必须引用真实存在的模型；mcpIds/skillIds/subAgentIds（若提供）里每个 id 也必须真实存在；
     * agentCode 格式、能力标识与高级参数取值范围做一处防御式校验（fast-fail），供 create/update 复用。
     *
     * @param selfId update 场景传当前智能体 id（用于拦截"子智能体包含自身"），create 场景传 null
     */
    private void validate(AgentSaveRequest request, Long selfId) {
        if (!AGENT_CODE_PATTERN.matcher(request.agentCode()).matches()) {
            throw new BizException(ResultCode.PARAM_INVALID, "agentCode 仅支持小写字母/数字/短横线");
        }
        if (modelConfigMapper.selectById(request.modelId()) == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "modelId 不存在: " + request.modelId());
        }
        validateBackupModels(request);
        if (!CollectionUtils.isEmpty(request.mcpIds())
            && mcpMapper.selectBatchIds(request.mcpIds()).size() != request.mcpIds().size()) {
            throw new BizException(ResultCode.PARAM_INVALID, "存在无效的 mcpIds");
        }
        if (!CollectionUtils.isEmpty(request.skillIds())
            && skillMapper.selectBatchIds(request.skillIds()).size() != request.skillIds().size()) {
            throw new BizException(ResultCode.PARAM_INVALID, "存在无效的 skillIds");
        }
        if (!CollectionUtils.isEmpty(request.systemToolIds())
            && systemToolMapper.selectBatchIds(request.systemToolIds()).size() != request.systemToolIds().size()) {
            throw new BizException(ResultCode.PARAM_INVALID, "存在无效的 systemToolIds");
        }
        if (!CollectionUtils.isEmpty(request.capabilities())
            && !VALID_CAPABILITIES.containsAll(request.capabilities())) {
            throw new BizException(ResultCode.PARAM_INVALID,
                "capabilities 仅支持 chat/vibecoding/subagent/plan/tasklist/skill-learning/dynamic-subagent/memory");
        }
        validateKnowledgeBaseIds(request);
        validateAdvancedParams(request);
        validateSubAgentIds(request, selfId);
    }

    /**
     * 知识库多选校验：每个 id 必须真实存在，且当前<b>启用</b>并且<b>连通性测试成功</b>——
     * 绑一个连不上的知识库，运行时每轮对话都要白白等一次超时，配置期就该 fast fail 拦住。
     * （下拉选项接口 {@code /options} 本就只返回可用项，这里是后端侧的兜底校验。）
     */
    private void validateKnowledgeBaseIds(AgentSaveRequest request) {
        if (CollectionUtils.isEmpty(request.knowledgeBaseIds())) {
            return;
        }
        List<AiKnowledgeBase> knowledgeBases = knowledgeBaseMapper.selectBatchIds(request.knowledgeBaseIds());
        if (knowledgeBases.size() != request.knowledgeBaseIds().size()) {
            throw new BizException(ResultCode.PARAM_INVALID, "存在无效的 knowledgeBaseIds");
        }
        for (AiKnowledgeBase knowledgeBase : knowledgeBases) {
            if (!Integer.valueOf(StatusFlags.ENABLED).equals(knowledgeBase.getStatus())
                || !Integer.valueOf(ConnectivityTestStatus.SUCCESS).equals(knowledgeBase.getTestStatus())) {
                throw new BizException(ResultCode.PARAM_INVALID,
                    "仅可绑定已启用且连通性测试成功的知识库: " + knowledgeBase.getKbName());
            }
        }
    }

    /** 高级参数取值范围校验（全部选填，null 表示用默认值不校验）。 */
    private void validateAdvancedParams(AgentSaveRequest request) {
        checkRange(request.maxIters(), MAX_ITERS_MIN, MAX_ITERS_MAX, "maxIters");
        checkRange(request.toolTimeoutSeconds(), TOOL_TIMEOUT_SECONDS_MIN, TOOL_TIMEOUT_SECONDS_MAX, "toolTimeoutSeconds");
        checkRange(request.toolMaxAttempts(), TOOL_MAX_ATTEMPTS_MIN, TOOL_MAX_ATTEMPTS_MAX, "toolMaxAttempts");
        checkRange(request.compressTriggerMsgs(), COMPRESS_TRIGGER_MSGS_MIN, COMPRESS_TRIGGER_MSGS_MAX, "compressTriggerMsgs");
        checkRange(request.compressKeepMsgs(), COMPRESS_KEEP_MSGS_MIN, COMPRESS_KEEP_MSGS_MAX, "compressKeepMsgs");
        if (request.compressTriggerMsgs() != null && request.compressKeepMsgs() != null
            && request.compressKeepMsgs() >= request.compressTriggerMsgs()) {
            throw new BizException(ResultCode.PARAM_INVALID, "compressKeepMsgs 必须小于 compressTriggerMsgs");
        }
    }

    /** 子智能体多选校验：勾选 subagent 能力后必须至少选一个（避免空转配置）；反之选了子智能体也必须勾能力；每个 id 真实存在且不含自身（update 场景）。 */
    private void validateSubAgentIds(AgentSaveRequest request, Long selfId) {
        boolean subagentChecked = !CollectionUtils.isEmpty(request.capabilities())
            && request.capabilities().contains(AgentCapabilities.SUBAGENT);
        if (CollectionUtils.isEmpty(request.subAgentIds())) {
            if (subagentChecked) {
                throw new BizException(ResultCode.PARAM_INVALID, "勾选 subagent 能力后需至少选择一个子智能体");
            }
            return;
        }
        if (!subagentChecked) {
            throw new BizException(ResultCode.PARAM_INVALID, "选择子智能体前需先勾选 subagent 能力");
        }
        if (selfId != null && request.subAgentIds().contains(selfId)) {
            throw new BizException(ResultCode.PARAM_INVALID, "子智能体不能包含自身");
        }
        if (agentMapper.selectBatchIds(request.subAgentIds()).size() != request.subAgentIds().size()) {
            throw new BizException(ResultCode.PARAM_INVALID, "存在无效的 subAgentIds");
        }
    }

    private void checkRange(Integer value, int min, int max, String fieldName) {
        if (value != null && (value < min || value > max)) {
            throw new BizException(ResultCode.PARAM_INVALID, fieldName + " 取值范围 [" + min + ", " + max + "]");
        }
    }

    /**
     * 备用模型校验：去重保序后，每个 id 必须真实存在于 ai_model_config，且不得等于主模型 id。
     * 校验失败抛业务异常（语义错误码 AGENT-BACKUP-MODEL-INVALID，落到 PARAM_INVALID 标准响应）。
     */
    private void validateBackupModels(AgentSaveRequest request) {
        List<Long> backupIds = distinctBackupIds(request.backupModelIds());
        if (backupIds.isEmpty()) {
            return;
        }
        if (backupIds.contains(request.modelId())) {
            throw new BizException(ResultCode.PARAM_INVALID, "备用模型不能包含主模型: " + request.modelId());
        }
        if (modelConfigMapper.selectBatchIds(backupIds).size() != backupIds.size()) {
            throw new BizException(ResultCode.PARAM_INVALID, "存在无效的 backupModelIds");
        }
    }

    /** 去重保序（LinkedHashSet 保留首次出现顺序），空集合安全返回空 List。 */
    private List<Long> distinctBackupIds(List<Long> backupModelIds) {
        if (CollectionUtils.isEmpty(backupModelIds)) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(backupModelIds));
    }

    /** 关联表整体替换：先清空该智能体现有关联行，再按本次提交的 ids 批量插入（比对差异做增量删改无必要，行数很少）。 */
    private void replaceRelations(Long agentId, List<Long> backupModelIds, List<Long> mcpIds, List<Long> skillIds,
                                  List<Long> systemToolIds, List<Long> subAgentIds, List<Long> knowledgeBaseIds) {
        agentBackupModelMapper.delete(new LambdaQueryWrapper<AiAgentBackupModel>().eq(AiAgentBackupModel::getAgentId, agentId));
        List<Long> orderedBackupIds = distinctBackupIds(backupModelIds);
        for (int i = 0; i < orderedBackupIds.size(); i++) {
            AiAgentBackupModel relation = new AiAgentBackupModel();
            relation.setAgentId(agentId);
            relation.setModelId(orderedBackupIds.get(i));
            relation.setSortOrder(i);
            agentBackupModelMapper.insert(relation);
        }
        agentMcpMapper.delete(new LambdaQueryWrapper<AiAgentMcp>().eq(AiAgentMcp::getAgentId, agentId));
        if (!CollectionUtils.isEmpty(mcpIds)) {
            for (Long mcpId : mcpIds) {
                AiAgentMcp relation = new AiAgentMcp();
                relation.setAgentId(agentId);
                relation.setMcpId(mcpId);
                agentMcpMapper.insert(relation);
            }
        }
        agentSkillMapper.delete(new LambdaQueryWrapper<AiAgentSkill>().eq(AiAgentSkill::getAgentId, agentId));
        if (!CollectionUtils.isEmpty(skillIds)) {
            for (Long skillId : skillIds) {
                AiAgentSkill relation = new AiAgentSkill();
                relation.setAgentId(agentId);
                relation.setSkillId(skillId);
                agentSkillMapper.insert(relation);
            }
        }
        agentSystemToolMapper.delete(new LambdaQueryWrapper<AiAgentSystemTool>().eq(AiAgentSystemTool::getAgentId, agentId));
        if (!CollectionUtils.isEmpty(systemToolIds)) {
            for (Long systemToolId : systemToolIds) {
                AiAgentSystemTool relation = new AiAgentSystemTool();
                relation.setAgentId(agentId);
                relation.setSystemToolId(systemToolId);
                agentSystemToolMapper.insert(relation);
            }
        }
        agentSubAgentMapper.delete(new LambdaQueryWrapper<AiAgentSubAgent>().eq(AiAgentSubAgent::getAgentId, agentId));
        if (!CollectionUtils.isEmpty(subAgentIds)) {
            for (Long subAgentId : subAgentIds) {
                AiAgentSubAgent relation = new AiAgentSubAgent();
                relation.setAgentId(agentId);
                relation.setSubAgentId(subAgentId);
                agentSubAgentMapper.insert(relation);
            }
        }
        agentKnowledgeBaseMapper.delete(
            new LambdaQueryWrapper<AiAgentKnowledgeBase>().eq(AiAgentKnowledgeBase::getAgentId, agentId));
        if (!CollectionUtils.isEmpty(knowledgeBaseIds)) {
            for (Long knowledgeBaseId : knowledgeBaseIds) {
                AiAgentKnowledgeBase relation = new AiAgentKnowledgeBase();
                relation.setAgentId(agentId);
                relation.setKnowledgeBaseId(knowledgeBaseId);
                agentKnowledgeBaseMapper.insert(relation);
            }
        }
    }

    private void fillFromRequest(AiAgent agent, AgentSaveRequest request) {
        agent.setAgentName(request.agentName());
        agent.setAgentCode(request.agentCode());
        agent.setModelId(request.modelId());
        agent.setSystemPrompt(request.systemPrompt());
        agent.setCapabilities(CollectionUtils.isEmpty(request.capabilities())
            ? AgentCapabilities.CHAT : String.join(AgentCapabilities.DELIMITER, request.capabilities()));
        agent.setIcon(request.icon());
        agent.setStatus(request.status() == null ? 1 : request.status());
        // 高级参数：null 直接落库（列可空，运行时工厂按 null=默认解释；实体上 updateStrategy=ALWAYS 保证可清空）
        agent.setMaxIters(request.maxIters());
        agent.setToolTimeoutSeconds(request.toolTimeoutSeconds());
        agent.setToolMaxAttempts(request.toolMaxAttempts());
        agent.setCompressTriggerMsgs(request.compressTriggerMsgs());
        agent.setCompressKeepMsgs(request.compressKeepMsgs());
    }

    private AgentVO toVo(AiAgent agent) {
        AgentVO vo = new AgentVO();
        vo.setId(agent.getId());
        vo.setAgentName(agent.getAgentName());
        vo.setAgentCode(agent.getAgentCode());
        vo.setModelId(agent.getModelId());
        AiModelConfig model = modelConfigMapper.selectById(agent.getModelId());
        vo.setModelName(model == null ? null : model.getModelName());
        fillBackupModels(vo, agent.getId());
        vo.setMcpIds(agentMcpMapper.selectList(new LambdaQueryWrapper<AiAgentMcp>().eq(AiAgentMcp::getAgentId, agent.getId()))
            .stream().map(AiAgentMcp::getMcpId).collect(Collectors.toList()));
        vo.setSkillIds(agentSkillMapper.selectList(new LambdaQueryWrapper<AiAgentSkill>().eq(AiAgentSkill::getAgentId, agent.getId()))
            .stream().map(AiAgentSkill::getSkillId).collect(Collectors.toList()));
        vo.setSystemToolIds(agentSystemToolMapper.selectList(new LambdaQueryWrapper<AiAgentSystemTool>().eq(AiAgentSystemTool::getAgentId, agent.getId()))
            .stream().map(AiAgentSystemTool::getSystemToolId).collect(Collectors.toList()));
        vo.setSystemPrompt(agent.getSystemPrompt());
        vo.setCapabilities(StringUtils.hasText(agent.getCapabilities())
            ? Arrays.asList(agent.getCapabilities().split(AgentCapabilities.DELIMITER)) : List.of());
        vo.setIcon(agent.getIcon());
        vo.setStatus(agent.getStatus());
        vo.setCreateTime(agent.getCreateTime());
        vo.setSubAgentIds(agentSubAgentMapper.selectList(
                new LambdaQueryWrapper<AiAgentSubAgent>().eq(AiAgentSubAgent::getAgentId, agent.getId()))
            .stream().map(AiAgentSubAgent::getSubAgentId).collect(Collectors.toList()));
        vo.setMaxIters(agent.getMaxIters());
        vo.setToolTimeoutSeconds(agent.getToolTimeoutSeconds());
        vo.setToolMaxAttempts(agent.getToolMaxAttempts());
        vo.setCompressTriggerMsgs(agent.getCompressTriggerMsgs());
        vo.setCompressKeepMsgs(agent.getCompressKeepMsgs());
        fillKnowledgeBases(vo, agent.getId());
        return vo;
    }

    /** 回填绑定的知识库 id 与名称：名称走一次批量查询（避免 N+1 单查），与 {@link #fillBackupModels} 同款手法。 */
    private void fillKnowledgeBases(AgentVO vo, Long agentId) {
        List<Long> knowledgeBaseIds = agentKnowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<AiAgentKnowledgeBase>().eq(AiAgentKnowledgeBase::getAgentId, agentId))
            .stream().map(AiAgentKnowledgeBase::getKnowledgeBaseId).collect(Collectors.toList());
        vo.setKnowledgeBaseIds(knowledgeBaseIds);
        if (CollectionUtils.isEmpty(knowledgeBaseIds)) {
            vo.setKnowledgeBaseNames(List.of());
            return;
        }
        Map<Long, String> nameById = knowledgeBaseMapper.selectBatchIds(knowledgeBaseIds).stream()
            .collect(Collectors.toMap(AiKnowledgeBase::getId, AiKnowledgeBase::getKbName));
        vo.setKnowledgeBaseNames(knowledgeBaseIds.stream().map(nameById::get).collect(Collectors.toList()));
    }

    /**
     * 回填有序备用模型 id 与名称：按 sort_order 升序取关联行，名称走一次批量查询（避免 N+1 单查）。
     */
    private void fillBackupModels(AgentVO vo, Long agentId) {
        List<Long> backupIds = agentBackupModelMapper.selectList(
                new LambdaQueryWrapper<AiAgentBackupModel>()
                    .eq(AiAgentBackupModel::getAgentId, agentId)
                    .orderByAsc(AiAgentBackupModel::getSortOrder))
            .stream().map(AiAgentBackupModel::getModelId).collect(Collectors.toList());
        vo.setBackupModelIds(backupIds);
        if (CollectionUtils.isEmpty(backupIds)) {
            vo.setBackupModelNames(List.of());
            return;
        }
        Map<Long, String> nameById = modelConfigMapper.selectBatchIds(backupIds).stream()
            .collect(Collectors.toMap(AiModelConfig::getId, AiModelConfig::getModelName));
        vo.setBackupModelNames(backupIds.stream().map(nameById::get).collect(Collectors.toList()));
    }

    /** 智能体存在性校验（全链路唯一防御点，包内 {@link AgentMemoryService} 复用）。 */
    AiAgent requireAgent(Long id) {
        AiAgent agent = agentMapper.selectById(id);
        if (agent == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "智能体不存在: " + id);
        }
        return agent;
    }
}
