package com.richard.fyoung.customeradmin.aiconfig.skill.service;

import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentSkillMapper;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillUploadFile;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillUploadParseResult;
import com.richard.fyoung.customeradmin.aiconfig.skill.entity.AiSkill;
import com.richard.fyoung.customeradmin.aiconfig.skill.entity.AiSkillFile;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillFileMapper;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import com.richard.fyoung.customerwork.data.skill.storage.SkillContentPublisher;
import com.richard.fyoung.customerwork.data.skill.storage.SkillStorageTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SkillService} 单测：
 * <ul>
 *   <li>{@code parseUploadContent}：.md 直接读正文（无附属文件）；.zip 以最浅层 SKILL.md 所在目录
 *       为技能根，收集根下全部附属文件（含子目录、二进制），找不到/类型不支持/路径穿越时报业务错误；</li>
 *   <li>附属文件落库：create/update 全量替换 ai_skill_file，update 未传 files 保持不变，delete 清理；</li>
 *   <li>存储目标编排：默认 local、目标未启用报错、发布失败回滚、取消勾选触发 remove、delete 尽力清理，
 *       附属文件随 publishFiles 一起发布。</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
class SkillServiceTest {

    private AiSkillMapper skillMapper;
    private AiSkillFileMapper skillFileMapper;
    private AiAgentSkillMapper agentSkillMapper;
    private AiAgentMapper agentMapper;
    private AgentInstanceCache agentInstanceCache;

    @BeforeEach
    void setUp() {
        skillMapper = mock(AiSkillMapper.class);
        skillFileMapper = mock(AiSkillFileMapper.class);
        agentSkillMapper = mock(AiAgentSkillMapper.class);
        agentMapper = mock(AiAgentMapper.class);
        agentInstanceCache = mock(AgentInstanceCache.class);
    }

    /** 用给定发布器组装 Service。 */
    private SkillService serviceWith(SkillContentPublisher... publishers) {
        return new SkillService(skillMapper, skillFileMapper, agentSkillMapper, agentMapper,
            agentInstanceCache, List.of(publishers));
    }

    private SkillContentPublisher publisher(SkillStorageTarget target) {
        SkillContentPublisher p = mock(SkillContentPublisher.class);
        when(p.target()).thenReturn(target);
        return p;
    }

    private SkillSaveRequest saveRequest(String name, String code, String content, List<String> targets,
                                         List<SkillUploadFile> files) {
        return new SkillSaveRequest(name, code, content, "desc", 1, targets, files);
    }

    // ---- parseUploadContent ----

    @Test
    void parseUploadContent_shouldReadMdFileDirectly_withoutFiles() {
        String markdown = "---\nname: test-skill\n---\n\n技能正文";
        MockMultipartFile file = new MockMultipartFile("file", "SKILL.md", "text/markdown",
            markdown.getBytes(StandardCharsets.UTF_8));

        SkillUploadParseResult result = serviceWith().parseUploadContent(file);

        assertEquals(markdown, result.content());
        assertTrue(result.files().isEmpty());
    }

    @Test
    void parseUploadContent_shouldCollectAllFilesUnderSkillRoot() throws IOException {
        String markdown = "---\nname: zipped-skill\n---\n\n压缩包里的技能正文";
        byte[] binary = {0x00, 0x01, (byte) 0xFF, 0x7F};
        Map<String, byte[]> zipContent = new LinkedHashMap<>();
        zipContent.put("my-skill/SKILL.md", markdown.getBytes(StandardCharsets.UTF_8));
        zipContent.put("my-skill/references/doc.md", "参考资料".getBytes(StandardCharsets.UTF_8));
        zipContent.put("my-skill/scripts/run.py", "print('hi')".getBytes(StandardCharsets.UTF_8));
        zipContent.put("my-skill/assets/logo.bin", binary);
        MockMultipartFile file = new MockMultipartFile("file", "my-skill.zip", "application/zip", buildZip(zipContent));

        SkillUploadParseResult result = serviceWith().parseUploadContent(file);

        assertEquals(markdown, result.content());
        assertEquals(3, result.files().size());
        Map<String, SkillUploadFile> byPath = result.files().stream()
            .collect(Collectors.toMap(SkillUploadFile::filePath, f -> f));
        assertTrue(byPath.containsKey("references/doc.md"));
        assertTrue(byPath.containsKey("scripts/run.py"));
        // 二进制文件字节精确往返（base64 编解码无损）
        assertArrayEquals(binary, Base64.getDecoder().decode(byPath.get("assets/logo.bin").contentBase64()));
        assertEquals(4L, byPath.get("assets/logo.bin").fileSize());
    }

    @Test
    void parseUploadContent_shouldUseShallowestSkillMdAsRoot_andSkipEntriesOutsideRoot() throws IOException {
        // 根目录 SKILL.md 与更深层的另一个 SKILL.md 并存时取最浅层；根外条目跳过
        Map<String, byte[]> zipContent = new LinkedHashMap<>();
        zipContent.put("pack/SKILL.md", "根技能".getBytes(StandardCharsets.UTF_8));
        zipContent.put("pack/nested/SKILL.md", "嵌套技能".getBytes(StandardCharsets.UTF_8));
        zipContent.put("other/readme.md", "根外文件".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile file = new MockMultipartFile("file", "a.zip", "application/zip", buildZip(zipContent));

        SkillUploadParseResult result = serviceWith().parseUploadContent(file);

        assertEquals("根技能", result.content());
        assertEquals(1, result.files().size());
        assertEquals("nested/SKILL.md", result.files().get(0).filePath());
    }

    @Test
    void parseUploadContent_shouldSkipMacosJunkEntries() throws IOException {
        Map<String, byte[]> zipContent = new LinkedHashMap<>();
        zipContent.put("SKILL.md", "正文".getBytes(StandardCharsets.UTF_8));
        zipContent.put("__MACOSX/._SKILL.md", "junk".getBytes(StandardCharsets.UTF_8));
        zipContent.put(".DS_Store", "junk".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile file = new MockMultipartFile("file", "a.zip", "application/zip", buildZip(zipContent));

        SkillUploadParseResult result = serviceWith().parseUploadContent(file);

        assertEquals("正文", result.content());
        assertTrue(result.files().isEmpty());
    }

    @Test
    void parseUploadContent_shouldMatchSkillMdCaseInsensitively() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "a.zip", "application/zip",
            buildZip(Map.of("skill.md", "内容".getBytes(StandardCharsets.UTF_8))));

        assertEquals("内容", serviceWith().parseUploadContent(file).content());
    }

    @Test
    void parseUploadContent_shouldRejectZipWithoutSkillMd() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "a.zip", "application/zip",
            buildZip(Map.of("readme.txt", "无关内容".getBytes(StandardCharsets.UTF_8))));

        assertThrows(BizException.class, () -> serviceWith().parseUploadContent(file));
    }

    @Test
    void parseUploadContent_shouldRejectUnsupportedExtension() {
        MockMultipartFile file = new MockMultipartFile("file", "skill.txt", "text/plain", "内容".getBytes(StandardCharsets.UTF_8));

        assertThrows(BizException.class, () -> serviceWith().parseUploadContent(file));
    }

    @Test
    void parseUploadContent_shouldRejectEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "skill.md", "text/markdown", new byte[0]);

        assertThrows(BizException.class, () -> serviceWith().parseUploadContent(file));
    }

    // ---- 附属文件落库 ----

    @Test
    void create_shouldPersistFiles_andPublishThem() {
        SkillContentPublisher local = publisher(SkillStorageTarget.MINIO);
        SkillService service = serviceWith(local);
        SkillUploadFile refDoc = new SkillUploadFile("references/doc.md", 6L,
            Base64.getEncoder().encodeToString("参考".getBytes(StandardCharsets.UTF_8)));
        // 发布前从库里读当前全量文件
        AiSkillFile stored = new AiSkillFile();
        stored.setFilePath("references/doc.md");
        stored.setContent("参考".getBytes(StandardCharsets.UTF_8));
        when(skillFileMapper.selectList(any())).thenReturn(List.of(stored));

        service.create(saveRequest("s1", "code-1", "正文", List.of("local"), List.of(refDoc)));

        ArgumentCaptor<AiSkillFile> captor = ArgumentCaptor.forClass(AiSkillFile.class);
        verify(skillFileMapper).insert(captor.capture());
        assertEquals("references/doc.md", captor.getValue().getFilePath());
        assertArrayEquals("参考".getBytes(StandardCharsets.UTF_8), captor.getValue().getContent());
        verify(local).publish("code-1", "正文");
        verify(local).publishFiles(any(), anyList());
    }

    @Test
    void create_shouldRejectTraversalFilePath() {
        SkillService service = serviceWith(publisher(SkillStorageTarget.MINIO));
        SkillUploadFile evil = new SkillUploadFile("../../etc/passwd", 1L,
            Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8)));

        assertThrows(BizException.class, () ->
            service.create(saveRequest("s1", "code-1", "正文", List.of("local"), List.of(evil))));
    }

    @Test
    void create_shouldRejectInvalidBase64() {
        SkillService service = serviceWith(publisher(SkillStorageTarget.MINIO));
        SkillUploadFile bad = new SkillUploadFile("references/doc.md", 1L, "!!not-base64!!");

        assertThrows(BizException.class, () ->
            service.create(saveRequest("s1", "code-1", "正文", List.of("local"), List.of(bad))));
    }

    @Test
    void update_shouldKeepExistingFiles_whenFilesNull() {
        AiSkill existing = new AiSkill();
        existing.setId(9L);
        existing.setSkillCode("code-9");
        existing.setStorageTargets("local");
        when(skillMapper.selectById(9L)).thenReturn(existing);
        SkillContentPublisher local = publisher(SkillStorageTarget.MINIO);
        SkillService service = serviceWith(local);

        service.update(9L, saveRequest("s9", "code-9", "新正文", List.of("local"), null));

        // files == null：不触发 ai_skill_file 删除/重建，但仍从库里读出现有文件发布
        verify(skillFileMapper, never()).delete(any());
        verify(skillFileMapper, never()).insert(any(AiSkillFile.class));
        verify(skillFileMapper, atLeastOnce()).selectList(any());
        verify(local).publishFiles(any(), anyList());
    }

    @Test
    void update_shouldReplaceFiles_whenFilesProvided() {
        AiSkill existing = new AiSkill();
        existing.setId(9L);
        existing.setSkillCode("code-9");
        existing.setStorageTargets("local");
        when(skillMapper.selectById(9L)).thenReturn(existing);
        SkillService service = serviceWith(publisher(SkillStorageTarget.MINIO));
        SkillUploadFile newFile = new SkillUploadFile("scripts/run.sh", 2L,
            Base64.getEncoder().encodeToString("ls".getBytes(StandardCharsets.UTF_8)));

        service.update(9L, saveRequest("s9", "code-9", "新正文", List.of("local"), List.of(newFile)));

        verify(skillFileMapper).delete(any());
        verify(skillFileMapper).insert(any(AiSkillFile.class));
    }

    @Test
    void delete_shouldRemoveFileRows() {
        AiSkill existing = new AiSkill();
        existing.setId(5L);
        existing.setSkillCode("code-5");
        existing.setStorageTargets("local");
        when(skillMapper.selectById(5L)).thenReturn(existing);
        when(agentSkillMapper.exists(any())).thenReturn(false);
        SkillService service = serviceWith(publisher(SkillStorageTarget.MINIO));

        service.delete(5L);

        verify(skillMapper).deleteById(5L);
        verify(skillFileMapper).delete(any());
    }

    // ---- 存储目标编排 ----

    @Test
    void create_shouldDefaultToLocal_whenTargetsEmpty() {
        SkillContentPublisher local = publisher(SkillStorageTarget.MINIO);
        SkillService service = serviceWith(local);

        service.create(saveRequest("s1", "code-1", "正文", null, null));

        verify(local).publish("code-1", "正文");
    }

    @Test
    void create_shouldRejectInvalidTarget() {
        SkillService service = serviceWith(publisher(SkillStorageTarget.MINIO));

        assertThrows(BizException.class, () ->
            service.create(saveRequest("s1", "code-1", "正文", List.of("ftp"), null)));
    }

    @Test
    void create_shouldFail_whenSelectedTargetNotEnabled() {
        // 只有 local 发布器，勾选 nacos 应报"目标未启用"
        SkillService service = serviceWith(publisher(SkillStorageTarget.MINIO));

        assertThrows(BizException.class, () ->
            service.create(saveRequest("s1", "code-1", "正文", List.of("local", "nacos"), null)));
    }

    @Test
    void create_shouldThrow_whenPublishFails() {
        SkillContentPublisher local = publisher(SkillStorageTarget.MINIO);
        doThrow(new RuntimeException("disk full")).when(local).publish(any(), any());
        SkillService service = serviceWith(local);

        // 发布失败抛业务异常（触发事务回滚）
        assertThrows(BizException.class, () ->
            service.create(saveRequest("s1", "code-1", "正文", List.of("local"), null)));
    }

    @Test
    void update_shouldRemoveCancelledTarget() {
        // 现有 skill 勾选 local+nacos，本次改为仅 local，应对 nacos 调 remove（用旧 skillCode）
        AiSkill existing = new AiSkill();
        existing.setId(9L);
        existing.setSkillCode("code-9");
        existing.setStorageTargets("local,nacos");
        when(skillMapper.selectById(9L)).thenReturn(existing);

        SkillContentPublisher local = publisher(SkillStorageTarget.MINIO);
        SkillContentPublisher nacos = publisher(SkillStorageTarget.NACOS);
        SkillService service = serviceWith(local, nacos);

        service.update(9L, saveRequest("s9", "code-9", "新正文", List.of("local"), null));

        verify(local).publish("code-9", "新正文");
        verify(nacos, never()).publish(any(), any());
        verify(nacos).remove("code-9");
    }

    @Test
    void update_shouldNotBlock_whenCancelledTargetRemoveFails() {
        AiSkill existing = new AiSkill();
        existing.setId(9L);
        existing.setSkillCode("code-9");
        existing.setStorageTargets("local,nacos");
        when(skillMapper.selectById(9L)).thenReturn(existing);

        SkillContentPublisher local = publisher(SkillStorageTarget.MINIO);
        SkillContentPublisher nacos = publisher(SkillStorageTarget.NACOS);
        doThrow(new RuntimeException("nacos down")).when(nacos).remove(any());
        SkillService service = serviceWith(local, nacos);

        // remove 失败仅记日志，不影响 update 成功
        service.update(9L, saveRequest("s9", "code-9", "新正文", List.of("local"), null));

        verify(local).publish("code-9", "新正文");
        verify(nacos).remove("code-9");
    }

    @Test
    void delete_shouldBestEffortRemoveStoredTargets() {
        AiSkill existing = new AiSkill();
        existing.setId(5L);
        existing.setSkillCode("code-5");
        existing.setStorageTargets("local");
        when(skillMapper.selectById(5L)).thenReturn(existing);
        when(agentSkillMapper.exists(any())).thenReturn(false);

        SkillContentPublisher local = publisher(SkillStorageTarget.MINIO);
        doThrow(new RuntimeException("io error")).when(local).remove(any());
        SkillService service = serviceWith(local);

        // remove 失败不阻断删除
        service.delete(5L);

        verify(skillMapper).deleteById(5L);
        verify(local).remove("code-5");
    }

    private byte[] buildZip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }
}
