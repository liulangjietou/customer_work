package com.richard.fyoung.customeradmin.aiconfig.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentDraftSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentDraftVO;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentDraft;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentDraftMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 仅管理个人编辑内容。恢复草稿只填充表单，正式保存仍走 AgentService 的校验与发布链路。
 * 使用项目注入的 ObjectMapper 序列化固定 DTO，不接收任意资源凭据或可执行扩展字段。
 */
@Service
public class AgentDraftService {
    private static final int MAX_CONFIGURATION_BYTES = 64 * 1024;
    private static final int MAX_TITLE_CHARS = 200;
    private final AiAgentDraftMapper mapper;
    private final AgentService agents;
    private final ObjectMapper json;

    public AgentDraftService(AiAgentDraftMapper mapper, AgentService agents, ObjectMapper json) {
        this.mapper = mapper;
        this.agents = agents;
        this.json = json;
    }

    /** 返回当前用户的草稿概要，不向同租户其他管理员共享个人提示词。 */
    public List<AgentDraftVO> list(long ownerUserId) {
        return mapper.selectList(new QueryWrapper<AiAgentDraft>()
                .select("id", "agent_id", "base_revision", "title", "version", "updated_at_ms")
                .eq("owner_user_id", ownerUserId).orderByDesc("updated_at_ms"))
            .stream().map(draft -> view(draft, false)).toList();
    }

    /** 同时按归属人和当前租户取详情；不可见草稿与不存在草稿响应一致。 */
    public AgentDraftVO get(String id, long ownerUserId) {
        return view(require(id, ownerUserId), true);
    }

    /** 首次保存的 ID 由浏览器固定；后续写入以版本比较防止多标签页互相覆盖。 */
    @Transactional(rollbackFor = Exception.class)
    public AgentDraftVO save(String id, long ownerUserId, AgentDraftSaveRequest request) {
        if ((request.agentId() == null) != (request.baseRevision() == null)) {
            throw new BizException(ResultCode.PARAM_INVALID, "编辑已有智能体时需要原配置版本");
        }
        if (request.agentId() != null) {
            agents.get(request.agentId());
        }
        String configuration = encode(request.configuration());
        if (configuration.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIGURATION_BYTES) {
            throw new BizException(ResultCode.PARAM_INVALID, "草稿配置不能超过 64 KB");
        }
        String title = request.configuration().agentName();
        if (!StringUtils.hasText(title)) {
            title = "未命名智能体";
        }
        if (title.length() > MAX_TITLE_CHARS) {
            throw new BizException(ResultCode.PARAM_INVALID, "草稿名称不能超过 200 个字符");
        }
        long now = System.currentTimeMillis();
        if (request.expectedVersion() == 0) {
            AiAgentDraft draft = new AiAgentDraft();
            draft.setId(id);
            draft.setOwnerUserId(ownerUserId);
            draft.setAgentId(request.agentId());
            draft.setBaseRevision(request.baseRevision());
            draft.setTitle(title);
            draft.setConfiguration(configuration);
            draft.setVersion(1L);
            draft.setUpdatedAtMs(now);
            try {
                mapper.insert(draft);
            } catch (DuplicateKeyException error) {
                throw new BizException(ResultCode.CONFIG_EDIT_CONFLICT);
            }
            return view(draft, true);
        }
        AiAgentDraft current = require(id, ownerUserId);
        if (!Objects.equals(current.getAgentId(), request.agentId())
            || !Objects.equals(current.getBaseRevision(), request.baseRevision())) {
            throw new BizException(ResultCode.CONFIG_EDIT_CONFLICT, "草稿关联的智能体或原配置版本已变化");
        }
        int changed = mapper.update(null, new UpdateWrapper<AiAgentDraft>()
            .eq("id", id).eq("owner_user_id", ownerUserId).eq("version", request.expectedVersion())
            .set("title", title).set("configuration", configuration).set("updated_at_ms", now)
            .setSql("version = version + 1"));
        if (changed != 1) {
            throw new BizException(ResultCode.CONFIG_EDIT_CONFLICT);
        }
        current.setTitle(title);
        current.setConfiguration(configuration);
        current.setUpdatedAtMs(now);
        current.setVersion(request.expectedVersion() + 1);
        return view(current, true);
    }

    /** 删除也比较版本，避免保存完成后的清理删除另一个标签页刚写入的内容。 */
    public void delete(String id, long ownerUserId, long expectedVersion) {
        int changed = mapper.delete(new QueryWrapper<AiAgentDraft>()
            .eq("id", id).eq("owner_user_id", ownerUserId).eq("version", expectedVersion));
        if (changed != 1) {
            throw new BizException(ResultCode.CONFIG_EDIT_CONFLICT);
        }
    }

    private AiAgentDraft require(String id, long ownerUserId) {
        AiAgentDraft draft = mapper.selectOne(new QueryWrapper<AiAgentDraft>()
            .eq("id", id).eq("owner_user_id", ownerUserId));
        if (draft == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        return draft;
    }

    private String encode(AgentSaveRequest configuration) {
        try {
            return json.writeValueAsString(configuration);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Agent draft serialization failed", error);
        }
    }

    private AgentDraftVO view(AiAgentDraft draft, boolean includeConfiguration) {
        try {
            return new AgentDraftVO(draft.getId(), draft.getAgentId(), draft.getBaseRevision(),
                draft.getTitle(), draft.getVersion(), draft.getUpdatedAtMs(), includeConfiguration
                    ? json.readValue(draft.getConfiguration(), AgentSaveRequest.class) : null);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Agent draft deserialization failed", error);
        }
    }
}
