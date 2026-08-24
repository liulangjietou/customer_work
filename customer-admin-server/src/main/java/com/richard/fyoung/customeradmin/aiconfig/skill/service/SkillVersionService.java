package com.richard.fyoung.customeradmin.aiconfig.skill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillVersionVO;
import com.richard.fyoung.customeradmin.aiconfig.skill.entity.AiSkill;
import com.richard.fyoung.customeradmin.aiconfig.skill.entity.AiSkillFile;
import com.richard.fyoung.customeradmin.aiconfig.skill.entity.AiSkillVersion;
import com.richard.fyoung.customeradmin.aiconfig.skill.entity.AiSkillVersionFile;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillFileMapper;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillMapper;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillVersionFileMapper;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillVersionMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.capability.eval.EvalFingerprint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Skill 不可变版本唯一写入/读取入口。 */
@Service
public class SkillVersionService {

    private final AiSkillMapper skillMapper;
    private final AiSkillFileMapper skillFileMapper;
    private final AiSkillVersionMapper versionMapper;
    private final AiSkillVersionFileMapper versionFileMapper;

    public SkillVersionService(AiSkillMapper skillMapper,
                               AiSkillFileMapper skillFileMapper,
                               AiSkillVersionMapper versionMapper,
                               AiSkillVersionFileMapper versionFileMapper) {
        this.skillMapper = skillMapper;
        this.skillFileMapper = skillFileMapper;
        this.versionMapper = versionMapper;
        this.versionFileMapper = versionFileMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public AiSkillVersion createVersion(Long skillId, String changeNote) {
        AiSkill skill = skillMapper.selectOne(new QueryWrapper<AiSkill>()
            .eq("id", skillId).last("FOR UPDATE"));
        if (skill == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "Skill 不存在: " + skillId);
        }
        List<AiSkillFile> files = skillFileMapper.selectList(
            new LambdaQueryWrapper<AiSkillFile>().eq(AiSkillFile::getSkillId, skillId))
            .stream().sorted(Comparator.comparing(AiSkillFile::getFilePath)).toList();
        int versionNo = skill.getLatestVersionNo() == null ? 1 : skill.getLatestVersionNo() + 1;

        AiSkillVersion version = new AiSkillVersion();
        version.setSkillId(skillId);
        version.setVersionNo(versionNo);
        version.setSkillName(skill.getSkillName());
        version.setSkillCode(skill.getSkillCode());
        version.setContent(skill.getContent());
        version.setDescription(skill.getDescription());
        version.setContentHash(contentHash(skill, files));
        version.setChangeNote(changeNote);
        versionMapper.insert(version);

        for (AiSkillFile file : files) {
            AiSkillVersionFile frozen = new AiSkillVersionFile();
            frozen.setSkillVersionId(version.getId());
            frozen.setFilePath(file.getFilePath());
            frozen.setFileSize(file.getFileSize());
            frozen.setContent(file.getContent());
            frozen.setContentHash(sha256(file.getContent()));
            versionFileMapper.insert(frozen);
        }
        skillMapper.update(null, new LambdaUpdateWrapper<AiSkill>()
            .eq(AiSkill::getId, skillId)
            .set(AiSkill::getCurrentVersionId, version.getId())
            .set(AiSkill::getLatestVersionNo, versionNo));
        return version;
    }

    public AiSkillVersion requireVersion(Long skillId, Long versionId) {
        AiSkillVersion version = versionMapper.selectOne(new LambdaQueryWrapper<AiSkillVersion>()
            .eq(AiSkillVersion::getId, versionId)
            .eq(AiSkillVersion::getSkillId, skillId));
        if (version == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND,
                "Skill 版本不存在: skillId=" + skillId + ", versionId=" + versionId);
        }
        return version;
    }

    public AiSkillVersion findVersion(Long versionId) {
        return versionId == null ? null : versionMapper.selectById(versionId);
    }

    public List<AiSkillVersionFile> files(Long versionId) {
        return versionFileMapper.selectList(new LambdaQueryWrapper<AiSkillVersionFile>()
            .eq(AiSkillVersionFile::getSkillVersionId, versionId)
            .orderByAsc(AiSkillVersionFile::getFilePath));
    }

    public List<SkillVersionVO> versions(Long skillId) {
        if (skillMapper.selectById(skillId) == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "Skill 不存在: " + skillId);
        }
        return versionMapper.selectList(new LambdaQueryWrapper<AiSkillVersion>()
                .eq(AiSkillVersion::getSkillId, skillId)
                .orderByDesc(AiSkillVersion::getVersionNo))
            .stream().map(this::toVo).toList();
    }

    private SkillVersionVO toVo(AiSkillVersion version) {
        SkillVersionVO vo = new SkillVersionVO();
        vo.setId(version.getId());
        vo.setVersionNo(version.getVersionNo());
        vo.setContentHash(version.getContentHash());
        vo.setChangeNote(version.getChangeNote());
        vo.setCreateTime(version.getCreateTime());
        return vo;
    }

    private String contentHash(AiSkill skill, List<AiSkillFile> files) {
        StringBuilder fileManifest = new StringBuilder();
        for (AiSkillFile file : files) {
            fileManifest.append(file.getFilePath()).append('|')
                .append(file.getFileSize()).append('|')
                .append(sha256(file.getContent())).append('\n');
        }
        return EvalFingerprint.of("skill-version-v1", skill.getSkillName(), skill.getSkillCode(),
            skill.getContent(), skill.getDescription(), fileManifest);
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content == null ? new byte[0] : content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
