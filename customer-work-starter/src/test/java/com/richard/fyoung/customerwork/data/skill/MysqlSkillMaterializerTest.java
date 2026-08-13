package com.richard.fyoung.customerwork.data.skill;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.richard.fyoung.customerwork.data.skill.entity.SkillDO;
import com.richard.fyoung.customerwork.data.skill.entity.SkillFileDO;
import com.richard.fyoung.customerwork.data.skill.mapper.SkillFileMapper;
import com.richard.fyoung.customerwork.data.skill.mapper.SkillMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MySQL 技能物化单测（离线，Mapper 用 mock）：正文与附属文件落盘、全量重建清理残留、
 * 以及两条路径安全防线（skillCode 白名单 / filePath 越界拦截）。
 *
 * <p>路径安全是本类的重点：这两张表可能由运维直接灌数据，starter 侧没有写入口做前置校验，
 * {@link MysqlSkillMaterializer} 是该链路的唯一防御点。</p>
 * @author owlzhangfq@gmail.com
 */
class MysqlSkillMaterializerTest {

    @Test
    void materialize_shouldWriteSkillMarkdownAndAttachments(@TempDir Path dir) throws IOException {
        SkillDO skill = skill(1L, "refund-handling", "# 退款处理\n");
        SkillFileDO file = skillFile(1L, "references/api.md", "接口说明");

        int count = materializer(List.of(skill), List.of(file)).materializeTo(dir);

        assertEquals(1, count);
        assertEquals("# 退款处理\n",
            Files.readString(dir.resolve("refund-handling/SKILL.md"), StandardCharsets.UTF_8));
        assertEquals("接口说明",
            Files.readString(dir.resolve("refund-handling/references/api.md"), StandardCharsets.UTF_8));
    }

    @Test
    void materialize_shouldWipeStaleArtifacts() throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("skill-materialize-");
        try {
            // 上一版残留：一个已从库里删掉的技能
            Files.createDirectories(dir.resolve("stale-skill"));
            Files.writeString(dir.resolve("stale-skill/SKILL.md"), "旧技能", StandardCharsets.UTF_8);

            materializer(List.of(skill(1L, "refund-handling", "# 退款处理\n")), List.of()).materializeTo(dir);

            assertFalse(Files.exists(dir.resolve("stale-skill")), "全量重建应清掉上一版残留");
            assertTrue(Files.exists(dir.resolve("refund-handling/SKILL.md")));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void materialize_shouldSkipSkillWithUnsafeCode(@TempDir Path dir) throws IOException {
        SkillDO evil = skill(1L, "../../escaped", "# 恶意技能\n");

        int count = materializer(List.of(evil), List.of()).materializeTo(dir);

        assertEquals(0, count, "skillCode 含路径穿越字符时应整个跳过");
        assertFalse(Files.exists(dir.getParent().getParent().resolve("escaped")),
            "不该在物化目录之外写出任何内容");
    }

    @Test
    void materialize_shouldSkipAttachmentEscapingSkillDir(@TempDir Path dir) throws IOException {
        SkillDO skill = skill(1L, "refund-handling", "# 退款处理\n");
        SkillFileDO evil = skillFile(1L, "../../escaped.md", "越界内容");
        SkillFileDO good = skillFile(1L, "references/api.md", "接口说明");

        int count = materializer(List.of(skill), List.of(evil, good)).materializeTo(dir);

        assertEquals(1, count, "越界附属文件只跳过它自己，不该拖垮整个技能");
        assertFalse(Files.exists(dir.resolve("escaped.md")), "越界文件不该被写出");
        assertTrue(Files.exists(dir.resolve("refund-handling/references/api.md")), "同技能其余文件应正常落盘");
    }

    @Test
    void materialize_shouldTolerateNullContent(@TempDir Path dir) throws IOException {
        SkillDO skill = skill(1L, "empty-skill", null);
        SkillFileDO file = skillFile(1L, "notes.md", null);

        materializer(List.of(skill), List.of(file)).materializeTo(dir);

        assertEquals("", Files.readString(dir.resolve("empty-skill/SKILL.md"), StandardCharsets.UTF_8));
        assertEquals(0, Files.size(dir.resolve("empty-skill/notes.md")));
    }

    @SuppressWarnings("unchecked")
    private static MysqlSkillMaterializer materializer(List<SkillDO> skills, List<SkillFileDO> files) {
        SkillMapper skillMapper = mock(SkillMapper.class);
        when(skillMapper.selectList(any(Wrapper.class))).thenReturn(skills);
        SkillFileMapper skillFileMapper = mock(SkillFileMapper.class);
        when(skillFileMapper.selectList(any(Wrapper.class))).thenReturn(files);
        return new MysqlSkillMaterializer(skillMapper, skillFileMapper);
    }

    private static SkillDO skill(Long id, String code, String content) {
        SkillDO skill = new SkillDO();
        skill.setId(id);
        skill.setSkillCode(code);
        skill.setSkillName(code);
        skill.setContent(content);
        skill.setEnabled(1);
        return skill;
    }

    private static SkillFileDO skillFile(Long skillId, String path, String content) {
        SkillFileDO file = new SkillFileDO();
        file.setSkillId(skillId);
        file.setFilePath(path);
        file.setContent(content == null ? null : content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
