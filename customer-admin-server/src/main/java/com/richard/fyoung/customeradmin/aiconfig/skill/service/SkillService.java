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
import com.richard.fyoung.customeradmin.aiconfig.skill.storage.SkillContentPublisher;
import com.richard.fyoung.customeradmin.aiconfig.skill.storage.SkillStorageTarget;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Skill 管理。
 *
 * <p>新建/编辑时除入库外，把 SKILL.md 正文发布到用户勾选的存储目标（local/nacos/sftp）。
 * 发布走 {@link SkillContentPublisher} SPI，发布失败让事务回滚，保证"保存成功=目标已上传"；
 * 取消勾选/删除时对相应目标做尽力而为的清理。智能体运行时消费仍从数据库读，不经这些目标。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    /** 存储目标发布相关错误码（日志占位符 / 业务异常消息用）。 */
    private static final String ERR_TARGET_INVALID = "SKILL-STORAGE-TARGET-INVALID";
    private static final String ERR_TARGET_DISABLED = "SKILL-STORAGE-TARGET-DISABLED";
    private static final String ERR_PUBLISH_FAIL = "SKILL-STORAGE-PUBLISH-FAIL";

    private static final String TARGET_DELIMITER = ",";

    private final AiSkillMapper skillMapper;
    private final AiAgentSkillMapper agentSkillMapper;
    private final AiAgentMapper agentMapper;
    private final AgentInstanceCache agentInstanceCache;
    /** 按目标索引的发布器；未启用的目标（nacos/sftp）在容器中无对应 Bean，故此处无键。 */
    private final Map<SkillStorageTarget, SkillContentPublisher> publishers;

    public SkillService(AiSkillMapper skillMapper, AiAgentSkillMapper agentSkillMapper, AiAgentMapper agentMapper,
                         AgentInstanceCache agentInstanceCache, List<SkillContentPublisher> contentPublishers) {
        this.skillMapper = skillMapper;
        this.agentSkillMapper = agentSkillMapper;
        this.agentMapper = agentMapper;
        this.agentInstanceCache = agentInstanceCache;
        this.publishers = new EnumMap<>(SkillStorageTarget.class);
        for (SkillContentPublisher publisher : contentPublishers) {
            this.publishers.put(publisher.target(), publisher);
        }
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

    @Transactional(rollbackFor = Exception.class)
    public void create(SkillSaveRequest request) {
        if (skillMapper.exists(new LambdaQueryWrapper<AiSkill>().eq(AiSkill::getSkillCode, request.skillCode()))) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "技能编码已存在");
        }
        List<SkillStorageTarget> targets = resolveTargets(request.storageTargets());
        AiSkill skill = new AiSkill();
        fillFromRequest(skill, request, targets);
        skillMapper.insert(skill);
        // 入库成功后发布，发布失败抛业务异常回滚事务，保证"保存成功=目标已上传"
        publishToTargets(skill.getSkillCode(), skill.getContent(), targets);
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, SkillSaveRequest request) {
        AiSkill skill = requireSkill(id);
        String oldSkillCode = skill.getSkillCode();
        List<SkillStorageTarget> oldTargets = parseStoredTargets(skill.getStorageTargets());
        if (!skill.getSkillCode().equals(request.skillCode())
            && skillMapper.exists(new LambdaQueryWrapper<AiSkill>().eq(AiSkill::getSkillCode, request.skillCode()))) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "技能编码已存在");
        }
        List<SkillStorageTarget> targets = resolveTargets(request.storageTargets());
        fillFromRequest(skill, request, targets);
        skillMapper.updateById(skill);
        // 覆盖发布到本次勾选的目标（失败回滚）
        publishToTargets(skill.getSkillCode(), skill.getContent(), targets);
        evictAgentsReferencingSkill(id);
        // 本次取消勾选的目标：尽力清理旧产物，失败仅记日志不阻断
        List<SkillStorageTarget> cancelled = new ArrayList<>(oldTargets);
        cancelled.removeAll(targets);
        removeFromTargetsQuietly(oldSkillCode, cancelled);
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

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        AiSkill skill = requireSkill(id);
        if (agentSkillMapper.exists(new LambdaQueryWrapper<AiAgentSkill>().eq(AiAgentSkill::getSkillId, id))) {
            throw new BizException(ResultCode.RESOURCE_IN_USE, "该 Skill 正被智能体引用，无法删除");
        }
        skillMapper.deleteById(id);
        // 落库的所有目标逐个尽力清理，失败仅记日志不阻断删除
        removeFromTargetsQuietly(skill.getSkillCode(), parseStoredTargets(skill.getStorageTargets()));
    }

    // ---- 存储目标发布/清理 ----

    /** 校验并规整请求的存储目标；空/null 默认 {@code [LOCAL]}，非法值 fast fail。 */
    private List<SkillStorageTarget> resolveTargets(List<String> raw) {
        if (CollectionUtils.isEmpty(raw)) {
            return List.of(SkillStorageTarget.LOCAL);
        }
        Set<SkillStorageTarget> targets = new LinkedHashSet<>();
        for (String code : raw) {
            SkillStorageTarget target = SkillStorageTarget.fromCode(code)
                .orElseThrow(() -> new BizException(ResultCode.PARAM_INVALID,
                    "[" + ERR_TARGET_INVALID + "] storageTargets 仅支持 local/nacos/sftp，非法值: " + code));
            targets.add(target);
        }
        return new ArrayList<>(targets);
    }

    /** 发布到指定目标；目标未启用或发布失败均抛业务异常（回滚事务）。 */
    private void publishToTargets(String skillCode, String content, List<SkillStorageTarget> targets) {
        for (SkillStorageTarget target : targets) {
            SkillContentPublisher publisher = publishers.get(target);
            if (publisher == null) {
                log.error("skill storage publish blocked, code={}, target={}, skillCode={}",
                    ERR_TARGET_DISABLED, target.getCode(), skillCode);
                throw new BizException(ResultCode.PARAM_INVALID,
                    "[" + ERR_TARGET_DISABLED + "] 存储目标未启用: " + target.getCode());
            }
            try {
                publisher.publish(skillCode, content);
            } catch (Exception e) {
                log.error("skill storage publish failed, code={}, target={}, skillCode={}",
                    ERR_PUBLISH_FAIL, target.getCode(), skillCode, e);
                throw new BizException(ResultCode.SYSTEM_ERROR,
                    "[" + ERR_PUBLISH_FAIL + "] 发布到存储目标失败: " + target.getCode());
            }
        }
    }

    /** 尽力而为地移除目标上的旧产物：目标未启用则跳过，异常仅记日志不上抛。 */
    private void removeFromTargetsQuietly(String skillCode, List<SkillStorageTarget> targets) {
        for (SkillStorageTarget target : targets) {
            SkillContentPublisher publisher = publishers.get(target);
            if (publisher == null) {
                continue;
            }
            try {
                publisher.remove(skillCode);
            } catch (Exception e) {
                log.error("skill storage remove failed, code={}, target={}, skillCode={}",
                    ERR_PUBLISH_FAIL, target.getCode(), skillCode, e);
            }
        }
    }

    /** 解析落库的逗号分隔目标串，非法/未知片段跳过（历史数据容错）。 */
    private List<SkillStorageTarget> parseStoredTargets(String stored) {
        if (!StringUtils.hasText(stored)) {
            return List.of();
        }
        List<SkillStorageTarget> targets = new ArrayList<>();
        for (String code : stored.split(TARGET_DELIMITER)) {
            SkillStorageTarget.fromCode(code).ifPresent(targets::add);
        }
        return targets;
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

    private void fillFromRequest(AiSkill skill, SkillSaveRequest request, List<SkillStorageTarget> targets) {
        skill.setSkillName(request.skillName());
        skill.setSkillCode(request.skillCode());
        skill.setContent(request.content());
        skill.setDescription(request.description());
        skill.setStatus(request.status() == null ? 1 : request.status());
        skill.setStorageTargets(targets.stream().map(SkillStorageTarget::getCode)
            .collect(Collectors.joining(TARGET_DELIMITER)));
    }

    private SkillVO toVo(AiSkill skill) {
        SkillVO vo = new SkillVO();
        vo.setId(skill.getId());
        vo.setSkillName(skill.getSkillName());
        vo.setSkillCode(skill.getSkillCode());
        vo.setContent(skill.getContent());
        vo.setDescription(skill.getDescription());
        vo.setStatus(skill.getStatus());
        vo.setStorageTargets(parseStoredTargets(skill.getStorageTargets()).stream()
            .map(SkillStorageTarget::getCode).collect(Collectors.toList()));
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
