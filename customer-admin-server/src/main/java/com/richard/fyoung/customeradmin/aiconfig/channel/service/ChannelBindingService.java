package com.richard.fyoung.customeradmin.aiconfig.channel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.dto.ChannelBindingSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.channel.dto.ChannelBindingVO;
import com.richard.fyoung.customeradmin.aiconfig.channel.entity.AiChannelBinding;
import com.richard.fyoung.customeradmin.aiconfig.channel.mapper.AiChannelBindingMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimePublishTaskMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 渠道-客服机器人运行配置绑定管理：CRUD + 手动重新发布。
 *
 * <p>绑定/编辑成功后若发布能力已启用，自动触发一次发布，把当前配置下发到 8080；
 * 「重新发布」按钮走 {@link #republish}（强制连通性探测，失败抛业务异常给前端明确反馈）。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class ChannelBindingService {

    private static final Logger log = LoggerFactory.getLogger(ChannelBindingService.class);

    private static final int STATUS_ENABLED = 1;

    private final AiChannelBindingMapper bindingMapper;
    private final AiAgentMapper agentMapper;
    private final CustomerWorkConfigPublisher publisher;
    private final RuntimePublishTaskMapper publishTaskMapper;

    public ChannelBindingService(AiChannelBindingMapper bindingMapper, AiAgentMapper agentMapper,
                                 CustomerWorkConfigPublisher publisher,
                                 RuntimePublishTaskMapper publishTaskMapper) {
        this.bindingMapper = bindingMapper;
        this.agentMapper = agentMapper;
        this.publisher = publisher;
        this.publishTaskMapper = publishTaskMapper;
    }

    /** 全量列表（行数很少，不分页）：按创建时间倒序，回填智能体名称。 */
    public List<ChannelBindingVO> list() {
        List<AiChannelBinding> bindings = bindingMapper.selectList(
            new LambdaQueryWrapper<AiChannelBinding>().orderByDesc(AiChannelBinding::getCreateTime));
        if (CollectionUtils.isEmpty(bindings)) {
            return List.of();
        }
        Map<Long, String> agentNames = agentMapper.selectBatchIds(
                bindings.stream().map(AiChannelBinding::getAgentId).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(AiAgent::getId, AiAgent::getAgentName, (a, b) -> a));
        List<Long> agentIds = bindings.stream().map(AiChannelBinding::getAgentId).distinct().toList();
        Map<Long, RuntimePublishTask> latestTaskByAgent = new java.util.LinkedHashMap<>();
        publishTaskMapper.selectList(new LambdaQueryWrapper<RuntimePublishTask>()
                .in(RuntimePublishTask::getTargetId, agentIds)
                .orderByDesc(RuntimePublishTask::getSeq))
            .forEach(task -> latestTaskByAgent.putIfAbsent(task.getTargetId(), task));
        return bindings.stream()
            .map(binding -> toVo(binding, agentNames::get, latestTaskByAgent.get(binding.getAgentId())))
            .collect(Collectors.toList());
    }

    /** 新建绑定：channelCode 唯一、agentId 必须存在；成功后触发一次发布。 */
    @Transactional(rollbackFor = Exception.class)
    public void create(ChannelBindingSaveRequest request) {
        requireAgent(request.agentId());
        assertChannelCodeUnique(request.channelCode(), null);
        assertSingleActiveRuntimeAgent(request.agentId(), request.status(), null);
        AiChannelBinding binding = new AiChannelBinding();
        binding.setChannelCode(request.channelCode());
        binding.setAgentId(request.agentId());
        binding.setStatus(request.status() == null ? STATUS_ENABLED : request.status());
        bindingMapper.insert(binding);
        publisher.publishForAgentId(binding.getAgentId());
    }

    /** 编辑绑定：改 channelCode / agentId / 状态（channelCode 排除自身查重）；成功后触发一次发布。 */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ChannelBindingSaveRequest request) {
        AiChannelBinding binding = requireBinding(id);
        requireAgent(request.agentId());
        assertChannelCodeUnique(request.channelCode(), id);
        assertSingleActiveRuntimeAgent(request.agentId(), request.status(), id);
        binding.setChannelCode(request.channelCode());
        binding.setAgentId(request.agentId());
        binding.setStatus(request.status() == null ? STATUS_ENABLED : request.status());
        bindingMapper.updateById(binding);
        publisher.publishForAgentId(binding.getAgentId());
    }

    public void delete(Long id) {
        requireBinding(id);
        bindingMapper.deleteById(id);
    }

    /** 手动重新发布：发布能力未启用直接拒绝；正常容器写入可靠任务并返回任务 ID。 */
    public String republish(String channelCode) {
        if (!publisher.isEnabled()) {
            throw new BizException(ResultCode.RUNTIME_PUBLISH_DISABLED);
        }
        try {
            return publisher.republishByChannel(channelCode);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.CHANNEL_BINDING_NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("manual republish failed, code={}, channel={}", "RUNTIME-REPUBLISH-FAIL", channelCode, e);
            throw new BizException(ResultCode.RUNTIME_PUBLISH_FAILED, e.getMessage());
        }
    }

    private ChannelBindingVO toVo(AiChannelBinding binding, Function<Long, String> agentNameResolver,
                                  RuntimePublishTask publishTask) {
        ChannelBindingVO vo = new ChannelBindingVO();
        vo.setId(binding.getId());
        vo.setChannelCode(binding.getChannelCode());
        vo.setAgentId(binding.getAgentId());
        vo.setAgentName(agentNameResolver.apply(binding.getAgentId()));
        vo.setStatus(binding.getStatus());
        if (publishTask != null) {
            vo.setPublishStatus(publishTask.getStatus());
            vo.setPublishRevision(publishTask.getRevision());
            vo.setPublishLastError(publishTask.getLastError());
            vo.setPublishUpdatedAtMs(publishTask.getUpdatedAtMs());
        }
        vo.setCreateTime(binding.getCreateTime());
        vo.setUpdateTime(binding.getUpdateTime());
        return vo;
    }

    /**
     * channelCode 唯一性校验（create/update 共用）：update 场景传自身 id 排除，避免改自己其它字段时误判重名。
     *
     * @param excludeId 排除的绑定 id；create 场景传 null
     */
    private void assertChannelCodeUnique(String channelCode, Long excludeId) {
        LambdaQueryWrapper<AiChannelBinding> wrapper = new LambdaQueryWrapper<AiChannelBinding>()
            .eq(AiChannelBinding::getChannelCode, channelCode);
        if (excludeId != null) {
            wrapper.ne(AiChannelBinding::getId, excludeId);
        }
        if (bindingMapper.exists(wrapper)) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "渠道编码已存在: " + channelCode);
        }
    }

    /**
     * 一个 Nacos dataId 对应一份全局运行时配置，不能让不同智能体互相覆盖最后写入者。
     * 多个渠道编码可以绑定同一个智能体；不同智能体必须使用独立 admin/dataId 部署。
     */
    private void assertSingleActiveRuntimeAgent(Long agentId, Integer status, Long excludeId) {
        int effectiveStatus = status == null ? STATUS_ENABLED : status;
        if (effectiveStatus != STATUS_ENABLED) {
            return;
        }
        LambdaQueryWrapper<AiChannelBinding> wrapper = new LambdaQueryWrapper<AiChannelBinding>()
            .eq(AiChannelBinding::getStatus, STATUS_ENABLED)
            .ne(AiChannelBinding::getAgentId, agentId);
        if (excludeId != null) {
            wrapper.ne(AiChannelBinding::getId, excludeId);
        }
        if (bindingMapper.exists(wrapper)) {
            throw new BizException(ResultCode.PARAM_INVALID,
                "同一运行时 dataId 只能启用一个智能体；请使用独立 admin/dataId 部署不同智能体");
        }
    }

    private void requireAgent(Long agentId) {
        if (agentMapper.selectById(agentId) == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "agentId 不存在: " + agentId);
        }
    }

    private AiChannelBinding requireBinding(Long id) {
        AiChannelBinding binding = bindingMapper.selectById(id);
        if (binding == null) {
            throw new BizException(ResultCode.CHANNEL_BINDING_NOT_FOUND, "渠道绑定不存在: " + id);
        }
        return binding;
    }
}
