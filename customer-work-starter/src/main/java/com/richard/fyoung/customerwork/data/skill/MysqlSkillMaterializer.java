package com.richard.fyoung.customerwork.data.skill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customerwork.core.constant.AgentFileNames;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import com.richard.fyoung.customerwork.data.skill.entity.SkillDO;
import com.richard.fyoung.customerwork.data.skill.entity.SkillFileDO;
import com.richard.fyoung.customerwork.data.skill.mapper.SkillFileMapper;
import com.richard.fyoung.customerwork.data.skill.mapper.SkillMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 把 MySQL 里的技能包物化成磁盘目录，供框架的 {@code FileSystemSkillRepository} 加载。
 *
 * <p><b>为什么还要落盘</b>：框架的技能仓库只从文件系统读，这是框架约束不是存储选型。本类让 MySQL 成为
 * 权威来源，磁盘目录退化为每次启动重建的缓存——物化前先整个删掉目标目录，杜绝上一版残留文件混进技能包
 * （删除的技能、改名的附属文件都会因此消失）。手法与 admin 侧 {@code AdminAgentInstanceFactory#buildSkillBox}
 * 一致。</p>
 *
 * <p><b>路径安全</b>：{@code skillCode} 与附属文件的 {@code filePath} 都会拼进磁盘路径，而这两张表可能由
 * 运维直接灌数据（starter 侧没有写入口做前置校验），故本类是该链路的<b>唯一防御点</b>：
 * skillCode 走字符白名单，filePath 逐条 normalize 后强校验必须仍落在该技能目录内，越界即跳过并记 error。
 * 不校验的话一条 {@code ../../etc/xxx} 就能写到工作目录之外。</p>
 * @author owlzhangfq@gmail.com
 */
public class MysqlSkillMaterializer {


    private static final Logger log = LoggerFactory.getLogger(MysqlSkillMaterializer.class);

    /** skillCode 白名单：它直接作为目录名，只允许字母/数字/连字符/下划线。 */
    private static final Pattern SAFE_SKILL_CODE = Pattern.compile("[A-Za-z0-9_-]+");
    private final SkillMapper skillMapper;
    private final SkillFileMapper skillFileMapper;

    public MysqlSkillMaterializer(SkillMapper skillMapper, SkillFileMapper skillFileMapper) {
        this.skillMapper = skillMapper;
        this.skillFileMapper = skillFileMapper;
    }

    /**
     * 读取全部启用的技能并物化到目标目录。
     *
     * @param targetDir 物化根目录（每个技能一个子目录，目录名 = skillCode）
     * @return 成功物化的技能数
     * @throws IOException 目录清理 / 创建失败（调用方决定是否降级，见 {@code CustomerServiceAgentFactory}）
     */
    public int materializeTo(Path targetDir) throws IOException {
        List<SkillDO> skills = skillMapper.selectList(
            new LambdaQueryWrapper<SkillDO>().eq(SkillDO::getEnabled, StatusFlags.ENABLED));

        // 全量重建：先清空再写，避免上一版残留（已删除的技能、改名的附属文件）混进技能包
        deleteRecursively(targetDir);
        Files.createDirectories(targetDir);

        int materialized = 0;
        for (SkillDO skill : skills) {
            if (!isSafeSkillCode(skill.getSkillCode())) {
                log.error("skip skill with unsafe code, code={}, skillCode={}",
                    "SKILL-CODE-UNSAFE", skill.getSkillCode());
                continue;
            }
            Path skillDir = targetDir.resolve(skill.getSkillCode());
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve(AgentFileNames.SKILL_MD),
                skill.getContent() == null ? "" : skill.getContent(), StandardCharsets.UTF_8);
            materializeFiles(skill, skillDir);
            materialized++;
        }
        log.info("skills materialized from mysql: count={} dir={}", materialized, targetDir.toAbsolutePath());
        return materialized;
    }

    /** 落该技能的全部附属文件；单个文件路径越界只跳过它，不影响同技能其余文件。 */
    private void materializeFiles(SkillDO skill, Path skillDir) throws IOException {
        List<SkillFileDO> files = skillFileMapper.selectList(
            new LambdaQueryWrapper<SkillFileDO>().eq(SkillFileDO::getSkillId, skill.getId()));
        Path skillRoot = skillDir.toAbsolutePath().normalize();
        for (SkillFileDO file : files) {
            Path target = skillRoot.resolve(file.getFilePath()).normalize();
            if (!target.startsWith(skillRoot)) {
                log.error("skip skill file escaping skill dir, code={}, skillCode={}, filePath={}",
                    "SKILL-FILE-PATH-TRAVERSAL", skill.getSkillCode(), file.getFilePath());
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.write(target, file.getContent() == null ? new byte[0] : file.getContent());
        }
    }

    private static boolean isSafeSkillCode(String skillCode) {
        return skillCode != null && !skillCode.isBlank() && SAFE_SKILL_CODE.matcher(skillCode).matches();
    }

    /** 递归删除目录（不存在则跳过），用于物化前清理旧产物。 */
    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
