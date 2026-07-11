package com.richard.fyoung.customeradmin.aiconfig.skill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentSkill;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentSkillMapper;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillVO;
import com.richard.fyoung.customeradmin.aiconfig.skill.entity.AiSkill;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Skill 管理。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SkillService {

    private final AiSkillMapper skillMapper;
    private final AiAgentSkillMapper agentSkillMapper;
    private final AiAgentMapper agentMapper;
    private final AgentInstanceCache agentInstanceCache;

    public SkillService(AiSkillMapper skillMapper, AiAgentSkillMapper agentSkillMapper, AiAgentMapper agentMapper,
                         AgentInstanceCache agentInstanceCache) {
        this.skillMapper = skillMapper;
        this.agentSkillMapper = agentSkillMapper;
        this.agentMapper = agentMapper;
        this.agentInstanceCache = agentInstanceCache;
    }

    public PageResult<SkillVO> page(PageQuery query) {
        LambdaQueryWrapper<AiSkill> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.like(AiSkill::getSkillName, query.getKeyword());
        }
        if (query.getStatus() != null) {
            wrapper.eq(AiSkill::getStatus, query.getStatus());
        }
        wrapper.orderBy(true, "asc".equalsIgnoreCase(query.getSortOrder()), AiSkill::getCreateTime);

        IPage<AiSkill> page = skillMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return PageResult.of(page.convert(this::toVo));
    }

    public SkillVO get(Long id) {
        return toVo(requireSkill(id));
    }

    public void create(SkillSaveRequest request) {
        if (skillMapper.exists(new LambdaQueryWrapper<AiSkill>().eq(AiSkill::getSkillCode, request.skillCode()))) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "技能编码已存在");
        }
        AiSkill skill = new AiSkill();
        fillFromRequest(skill, request);
        skillMapper.insert(skill);
    }

    public void update(Long id, SkillSaveRequest request) {
        AiSkill skill = requireSkill(id);
        if (!skill.getSkillCode().equals(request.skillCode())
            && skillMapper.exists(new LambdaQueryWrapper<AiSkill>().eq(AiSkill::getSkillCode, request.skillCode()))) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "技能编码已存在");
        }
        fillFromRequest(skill, request);
        skillMapper.updateById(skill);
        evictAgentsReferencingSkill(id);
    }

    /** Skill 内容变更（content 等）会让引用它的智能体运行时用上旧 SKILL.md，需一并失效。 */
    private void evictAgentsReferencingSkill(Long skillId) {
        List<Long> agentIds = agentSkillMapper.selectList(new LambdaQueryWrapper<AiAgentSkill>().eq(AiAgentSkill::getSkillId, skillId))
            .stream().map(AiAgentSkill::getAgentId).collect(Collectors.toList());
        if (agentIds.isEmpty()) {
            return;
        }
        List<String> agentCodes = agentMapper.selectBatchIds(agentIds).stream()
            .map(AiAgent::getAgentCode).collect(Collectors.toList());
        agentInstanceCache.evictAll(agentCodes);
    }

    public void delete(Long id) {
        requireSkill(id);
        if (agentSkillMapper.exists(new LambdaQueryWrapper<AiAgentSkill>().eq(AiAgentSkill::getSkillId, id))) {
            throw new BizException(ResultCode.RESOURCE_IN_USE, "该 Skill 正被智能体引用，无法删除");
        }
        skillMapper.deleteById(id);
    }

    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final long MAX_UPLOAD_BYTES = 5 * 1024 * 1024;

    /**
     * 解析上传文件为 SKILL.md 正文：{@code .md} 直接整篇当正文；{@code .zip} 在包内（任意目录层级）
     * 查找文件名为 {@code SKILL.md}（大小写不敏感）的条目取其内容——按需求约定，zip 只是 SKILL.md 的
     * 另一种上传方式，不落盘、不解压其余 references/examples/scripts 等附属文件，解析结果直接回填
     * 前端表单的 content 字段，仍走既有的 create/update 接口保存，不新增数据库结构。
     */
    public String parseUploadContent(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.PARAM_MISSING, "请选择要上传的文件");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new BizException(ResultCode.PARAM_INVALID, "文件大小超过 5MB 限制");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        try {
            if (filename.endsWith(".md")) {
                return new String(file.getBytes(), StandardCharsets.UTF_8);
            }
            if (filename.endsWith(".zip")) {
                return extractSkillMdFromZip(file);
            }
        } catch (IOException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "文件读取失败: " + e.getMessage());
        }
        throw new BizException(ResultCode.PARAM_INVALID, "仅支持上传 .md 或 .zip 文件");
    }

    private String extractSkillMdFromZip(MultipartFile file) throws IOException {
        try (ZipInputStream zipIn = new ZipInputStream(new ByteArrayInputStream(file.getBytes()), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                String entryFileName = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
                if (SKILL_FILE_NAME.equalsIgnoreCase(entryFileName)) {
                    return new String(zipIn.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new BizException(ResultCode.PARAM_INVALID, "zip 压缩包中未找到 SKILL.md");
    }

    private void fillFromRequest(AiSkill skill, SkillSaveRequest request) {
        skill.setSkillName(request.skillName());
        skill.setSkillCode(request.skillCode());
        skill.setContent(request.content());
        skill.setDescription(request.description());
        skill.setStatus(request.status() == null ? 1 : request.status());
    }

    private SkillVO toVo(AiSkill skill) {
        SkillVO vo = new SkillVO();
        vo.setId(skill.getId());
        vo.setSkillName(skill.getSkillName());
        vo.setSkillCode(skill.getSkillCode());
        vo.setContent(skill.getContent());
        vo.setDescription(skill.getDescription());
        vo.setStatus(skill.getStatus());
        vo.setCreateTime(skill.getCreateTime());
        return vo;
    }

    private AiSkill requireSkill(Long id) {
        AiSkill skill = skillMapper.selectById(id);
        if (skill == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "Skill 不存在: " + id);
        }
        return skill;
    }
}
