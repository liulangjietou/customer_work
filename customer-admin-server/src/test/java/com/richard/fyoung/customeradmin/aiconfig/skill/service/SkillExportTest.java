package com.richard.fyoung.customeradmin.aiconfig.skill.service;

import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentSkillMapper;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillExportPackage;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillUploadFile;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillUploadParseResult;
import com.richard.fyoung.customeradmin.aiconfig.skill.entity.AiSkill;
import com.richard.fyoung.customeradmin.aiconfig.skill.entity.AiSkillFile;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillFileMapper;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 技能包导出（{@code exportZip}）。
 *
 * <p>核心约束是<b>与上传解析往返对称</b>：导出的 zip 必须能被 {@code parseUploadContent} 原样解析回来，
 * 否则"下载一个 skill 拿到别的环境导入"这条路走不通——而那正是这个功能的主要用途。
 * 因此本测试不只断言 zip 里有哪些条目，而是真的把导出结果再解析一遍比对。</p>
 * @author owlzhangfq@gmail.com
 */
class SkillExportTest {

    private static final long SKILL_ID = 7L;

    private AiSkillMapper skillMapper;
    private AiSkillFileMapper skillFileMapper;
    private SkillService service;

    @BeforeEach
    void setUp() {
        skillMapper = mock(AiSkillMapper.class);
        skillFileMapper = mock(AiSkillFileMapper.class);
        service = new SkillService(skillMapper, skillFileMapper, mock(AiAgentSkillMapper.class),
            mock(AiAgentMapper.class), mock(AgentInstanceCache.class), List.of());
    }

    @Test
    void shouldPackSkillMdAndFilesUnderCodeDirectory() {
        givenSkill("my-skill", "# 我的技能\n正文");
        givenFiles(file("references/api.md", "接口说明"), file("scripts/run.sh", "echo hi"));

        SkillExportPackage pkg = service.exportZip(SKILL_ID);

        assertEquals("my-skill.zip", pkg.filename());
        Map<String, byte[]> entries = unzip(pkg.content());
        assertEquals(List.of("my-skill/SKILL.md", "my-skill/references/api.md", "my-skill/scripts/run.sh"),
            List.copyOf(entries.keySet()));
        assertEquals("# 我的技能\n正文", new String(entries.get("my-skill/SKILL.md"), StandardCharsets.UTF_8));
        assertEquals("接口说明", new String(entries.get("my-skill/references/api.md"), StandardCharsets.UTF_8));
    }

    @Test
    void exportedZip_shouldBeReimportable() {
        // 往返对称是这个功能的立身之本：导出的包必须能从"新建 Skill"的上传入口原样导回
        givenSkill("my-skill", "# 我的技能\n正文");
        givenFiles(file("references/api.md", "接口说明"), file("scripts/run.sh", "echo hi"));

        SkillExportPackage pkg = service.exportZip(SKILL_ID);
        SkillUploadParseResult parsed = service.parseUploadContent(
            new MockMultipartFile("file", "my-skill.zip", "application/zip", pkg.content()));

        assertEquals("# 我的技能\n正文", parsed.content(), "SKILL.md 正文必须原样还原");
        Map<String, String> files = new LinkedHashMap<>();
        for (SkillUploadFile f : parsed.files()) {
            files.put(f.filePath(), new String(Base64.getDecoder().decode(f.contentBase64()), StandardCharsets.UTF_8));
        }
        assertEquals(2, files.size());
        assertEquals("接口说明", files.get("references/api.md"), "附属文件路径与内容都要还原");
        assertEquals("echo hi", files.get("scripts/run.sh"));
    }

    @Test
    void binaryFile_shouldSurviveRoundTrip() {
        // 附属文件是 LONGBLOB，可能是图片/字体这类二进制，不能按文本处理
        byte[] binary = new byte[]{0x00, (byte) 0xFF, 0x10, (byte) 0x89, 0x50, 0x4E, 0x47};
        givenSkill("bin-skill", "# 正文");
        AiSkillFile row = new AiSkillFile();
        row.setFilePath("assets/logo.png");
        row.setContent(binary);
        givenFiles(row);

        Map<String, byte[]> entries = unzip(service.exportZip(SKILL_ID).content());

        assertArrayEquals(binary, entries.get("bin-skill/assets/logo.png"));
    }

    @Test
    void nullContentFile_shouldStillBeIncludedAsEmpty() {
        // 跳过空内容的文件会让重新导入后清单凭空少几项，导出就不是"这个 skill 的全部"了
        givenSkill("my-skill", "# 正文");
        AiSkillFile empty = new AiSkillFile();
        empty.setFilePath("references/placeholder.md");
        empty.setContent(null);
        givenFiles(empty);

        Map<String, byte[]> entries = unzip(service.exportZip(SKILL_ID).content());

        assertTrue(entries.containsKey("my-skill/references/placeholder.md"));
        assertEquals(0, entries.get("my-skill/references/placeholder.md").length);
    }

    @Test
    void chineseCode_shouldBeKept() {
        // 中文包名本身合法（zip 与 Content-Disposition 都按 UTF-8 处理），不该被净化掉
        givenSkill("Apollo查值", "# 正文");
        givenFiles();

        SkillExportPackage pkg = service.exportZip(SKILL_ID);

        assertEquals("Apollo查值.zip", pkg.filename());
        assertTrue(unzip(pkg.content()).containsKey("Apollo查值/SKILL.md"));
    }

    @Test
    void codeWithPathSeparator_shouldBeSanitized() {
        // skillCode 是用户填的，带 / 或 .. 会写出目录穿越的 zip 条目，解压方按路径还原就落到目标目录之外
        givenSkill("../../etc/passwd", "# 正文");
        givenFiles();

        SkillExportPackage pkg = service.exportZip(SKILL_ID);

        assertFalse(pkg.filename().contains("/"), "文件名里不能留下路径分隔符");
        assertTrue(unzip(pkg.content()).keySet().stream().noneMatch(name -> name.contains("../")),
            "zip 条目里不能出现目录穿越");
    }

    @Test
    void missingSkill_shouldFailFast() {
        when(skillMapper.selectById(SKILL_ID)).thenReturn(null);

        assertThrows(BizException.class, () -> service.exportZip(SKILL_ID));
    }

    private void givenSkill(String code, String content) {
        AiSkill skill = new AiSkill();
        skill.setId(SKILL_ID);
        skill.setSkillCode(code);
        skill.setSkillName(code);
        skill.setContent(content);
        when(skillMapper.selectById(SKILL_ID)).thenReturn(skill);
    }

    private void givenFiles(AiSkillFile... files) {
        when(skillFileMapper.selectList(any())).thenReturn(List.of(files));
    }

    private AiSkillFile file(String path, String content) {
        AiSkillFile row = new AiSkillFile();
        row.setFilePath(path);
        row.setContent(content.getBytes(StandardCharsets.UTF_8));
        return row;
    }

    private Map<String, byte[]> unzip(byte[] zipBytes) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        } catch (Exception e) {
            throw new IllegalStateException("解压失败", e);
        }
        return entries;
    }
}
