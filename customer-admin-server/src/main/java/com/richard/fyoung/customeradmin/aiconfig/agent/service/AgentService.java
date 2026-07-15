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
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentBackupModelMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentSkillMapper;
import com.richard.fyoung.customeradmin.aiconfig.mcp.mapper.AiMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelConfigMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelConfigService;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillMapper;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.entity.AiAgentSystemTool;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.mapper.AiAgentSystemToolMapper;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.mapper.AiSystemToolMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.menu.service.MenuVersionHolder;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
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
    private static final Set<String> VALID_CAPABILITIES = Set.of("chat", "vibecoding");
    private static final String CAPABILITY_DELIMITER = ",";
    /** 保存门禁的连通性测试等待上限（秒）：给 ModelConfigService 内部 10s 硬超时留余量。 */
    private static final long MODEL_TEST_GATE_TIMEOUT_SECONDS = 12;
    /** 主模型连通性门禁失败的日志错误码。 */
    private static final String CODE_MODEL_TEST_FAIL = "AGENT-MODEL-TEST-FAIL";

    private final AiAgentMapper agentMapper;
    private final AiAgentMcpMapper agentMcpMapper;
    private final AiAgentSkillMapper agentSkillMapper;
    private final AiAgentBackupModelMapper agentBackupModelMapper;
    private final AiModelConfigMapper modelConfigMapper;
    private final AiMcpMapper mcpMapper;
    private final AiSkillMapper skillMapper;
    private final AiAgentSystemToolMapper agentSystemToolMapper;
    private final AiSystemToolMapper systemToolMapper;
    private final MenuVersionHolder menuVersionHolder;
    private final AgentInstanceCache agentInstanceCache;
    private final ModelConfigService modelConfigService;

    public AgentService(AiAgentMapper agentMapper, AiAgentMcpMapper agentMcpMapper,
                         AiAgentSkillMapper agentSkillMapper, AiAgentBackupModelMapper agentBackupModelMapper,
                         AiModelConfigMapper modelConfigMapper,
                         AiMcpMapper mcpMapper, AiSkillMapper skillMapper,
                         AiAgentSystemToolMapper agentSystemToolMapper, AiSystemToolMapper systemToolMapper,
                         MenuVersionHolder menuVersionHolder, AgentInstanceCache agentInstanceCache,
                         ModelConfigService modelConfigService) {
        this.agentMapper = agentMapper;
        this.agentMcpMapper = agentMcpMapper;
        this.agentSkillMapper = agentSkillMapper;
        this.agentBackupModelMapper = agentBackupModelMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.mcpMapper = mcpMapper;
        this.skillMapper = skillMapper;
        this.agentSystemToolMapper = agentSystemToolMapper;
        this.systemToolMapper = systemToolMapper;
        this.menuVersionHolder = menuVersionHolder;
        this.agentInstanceCache = agentInstanceCache;
        this.modelConfigService = modelConfigService;
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
        validate(request);
        // 新建：主模型必须实测连通性通过才允许保存
        assertPrimaryModelConnectivity(request.modelId());
        AiAgent agent = new AiAgent();
        fillFromRequest(agent, request);
        agentMapper.insert(agent);
        replaceRelations(agent.getId(), request.backupModelIds(), request.mcpIds(), request.skillIds(), request.systemToolIds());
        menuVersionHolder.bump();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, AgentSaveRequest request) {
        AiAgent agent = requireAgent(id);
        String oldAgentCode = agent.getAgentCode();
        Long oldModelId = agent.getModelId();
        validate(request);
        // 编辑：仅当主模型变更时才实测连通性
        if (!request.modelId().equals(oldModelId)) {
            assertPrimaryModelConnectivity(request.modelId());
        }
        fillFromRequest(agent, request);
        agentMapper.updateById(agent);
        replaceRelations(id, request.backupModelIds(), request.mcpIds(), request.skillIds(), request.systemToolIds());
        menuVersionHolder.bump();
        // agentCode 理论上不应改变，但仍按新旧两个 code 双清，避免缓存键错位残留旧实例
        agentInstanceCache.evict(oldAgentCode);
        agentInstanceCache.evict(request.agentCode());
    }

    /**
     * 保存门禁：同步实测主模型连通性，非成功即抛业务异常阻止保存。防御只做这一处（后端保存链路），
     * 运行时不再重复校验（运行时靠 FailoverModel 主备容错兜底）。
     */
    private void assertPrimaryModelConnectivity(Long modelId) {
        try {
            ModelTestResult result = modelConfigService.testConnectivity(modelId)
                .get(MODEL_TEST_GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (result.testStatus() != ModelTestResult.STATUS_SUCCESS) {
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
        menuVersionHolder.bump();
        agentInstanceCache.evict(agent.getAgentCode());
    }

    /** 启用/停用（生命周期），不改动其余字段。 */
    public void updateStatus(Long id, int status) {
        AiAgent agent = requireAgent(id);
        AiAgent update = new AiAgent();
        update.setId(id);
        update.setStatus(status);
        agentMapper.updateById(update);
        menuVersionHolder.bump();
        agentInstanceCache.evict(agent.getAgentCode());
    }

    /** modelId 必须引用真实存在的模型；mcpIds/skillIds（若提供）里每个 id 也必须真实存在；agentCode 格式与能力标识做一处防御式校验，供 create/update 复用。 */
    private void validate(AgentSaveRequest request) {
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
            throw new BizException(ResultCode.PARAM_INVALID, "capabilities 仅支持 chat/vibecoding");
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
    private void replaceRelations(Long agentId, List<Long> backupModelIds,
                                  List<Long> mcpIds, List<Long> skillIds, List<Long> systemToolIds) {
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
    }

    private void fillFromRequest(AiAgent agent, AgentSaveRequest request) {
        agent.setAgentName(request.agentName());
        agent.setAgentCode(request.agentCode());
        agent.setModelId(request.modelId());
        agent.setSystemPrompt(request.systemPrompt());
        agent.setCapabilities(CollectionUtils.isEmpty(request.capabilities())
            ? "chat" : String.join(CAPABILITY_DELIMITER, request.capabilities()));
        agent.setIcon(request.icon());
        agent.setStatus(request.status() == null ? 1 : request.status());
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
            ? Arrays.asList(agent.getCapabilities().split(CAPABILITY_DELIMITER)) : List.of());
        vo.setIcon(agent.getIcon());
        vo.setStatus(agent.getStatus());
        vo.setCreateTime(agent.getCreateTime());
        return vo;
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

    private AiAgent requireAgent(Long id) {
        AiAgent agent = agentMapper.selectById(id);
        if (agent == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "智能体不存在: " + id);
        }
        return agent;
    }
}
