package com.richard.fyoung.customeradmin.aiconfig.skill.service;

import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentSkillMapper;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.skill.entity.AiSkill;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillMapper;
import com.richard.fyoung.customeradmin.aiconfig.skill.storage.SkillContentPublisher;
import com.richard.fyoung.customeradmin.aiconfig.skill.storage.SkillStorageTarget;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SkillService} 单测：
 * <ul>
 *   <li>{@code parseUploadContent}：.md 直接读正文，.zip 找 SKILL.md（任意目录层级、大小写不敏感），
 *       找不到/文件类型不支持时报业务错误；</li>
 *   <li>存储目标编排：默认 local、目标未启用报错、发布失败回滚、取消勾选触发 remove、delete 尽力清理。</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
class SkillServiceTest {

    private AiSkillMapper skillMapper;
    private AiAgentSkillMapper agentSkillMapper;
    private AiAgentMapper agentMapper;
    private AgentInstanceCache agentInstanceCache;

    @BeforeEach
    void setUp() {
        skillMapper = mock(AiSkillMapper.class);
        agentSkillMapper = mock(AiAgentSkillMapper.class);
        agentMapper = mock(AiAgentMapper.class);
        agentInstanceCache = mock(AgentInstanceCache.class);
    }

    /** 用给定发布器组装 Service。 */
    private SkillService serviceWith(SkillContentPublisher... publishers) {
        return new SkillService(skillMapper, agentSkillMapper, agentMapper, agentInstanceCache, List.of(publishers));
    }

    private SkillContentPublisher publisher(SkillStorageTarget target) {
        SkillContentPublisher p = mock(SkillContentPublisher.class);
        when(p.target()).thenReturn(target);
        return p;
    }

    // ---- parseUploadContent ----

    @Test
    void parseUploadContent_shouldReadMdFileDirectly() {
        String markdown = "---\nname: test-skill\n---\n\n技能正文";
        MockMultipartFile file = new MockMultipartFile("file", "SKILL.md", "text/markdown",
            markdown.getBytes(StandardCharsets.UTF_8));

        String result = serviceWith().parseUploadContent(file);

        assertEquals(markdown, result);
    }

    @Test
    void parseUploadContent_shouldExtractSkillMdFromZip_atNestedPath() throws IOException {
        String markdown = "---\nname: zipped-skill\n---\n\n压缩包里的技能正文";
        byte[] zipBytes = buildZip("my-skill/SKILL.md", markdown, "my-skill/references/doc.txt", "参考资料");
        MockMultipartFile file = new MockMultipartFile("file", "my-skill.zip", "application/zip", zipBytes);

        String result = serviceWith().parseUploadContent(file);

        assertEquals(markdown, result);
    }

    @Test
    void parseUploadContent_shouldMatchSkillMdCaseInsensitively() throws IOException {
        String markdown = "内容";
        byte[] zipBytes = buildZip("skill.md", markdown);
        MockMultipartFile file = new MockMultipartFile("file", "a.zip", "application/zip", zipBytes);

        String result = serviceWith().parseUploadContent(file);

        assertEquals(markdown, result);
    }

    @Test
    void parseUploadContent_shouldRejectZipWithoutSkillMd() throws IOException {
        byte[] zipBytes = buildZip("readme.txt", "无关内容");
        MockMultipartFile file = new MockMultipartFile("file", "a.zip", "application/zip", zipBytes);

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

    // ---- 存储目标编排 ----

    @Test
    void create_shouldDefaultToLocal_whenTargetsEmpty() {
        SkillContentPublisher local = publisher(SkillStorageTarget.LOCAL);
        SkillService service = serviceWith(local);

        service.create(new SkillSaveRequest("s1", "code-1", "正文", "desc", 1, null));

        verify(local).publish("code-1", "正文");
    }

    @Test
    void create_shouldRejectInvalidTarget() {
        SkillService service = serviceWith(publisher(SkillStorageTarget.LOCAL));

        assertThrows(BizException.class, () ->
            service.create(new SkillSaveRequest("s1", "code-1", "正文", "desc", 1, List.of("ftp"))));
    }

    @Test
    void create_shouldFail_whenSelectedTargetNotEnabled() {
        // 只有 local 发布器，勾选 nacos 应报"目标未启用"
        SkillService service = serviceWith(publisher(SkillStorageTarget.LOCAL));

        assertThrows(BizException.class, () ->
            service.create(new SkillSaveRequest("s1", "code-1", "正文", "desc", 1, List.of("local", "nacos"))));
    }

    @Test
    void create_shouldThrow_whenPublishFails() {
        SkillContentPublisher local = publisher(SkillStorageTarget.LOCAL);
        doThrow(new RuntimeException("disk full")).when(local).publish(any(), any());
        SkillService service = serviceWith(local);

        // 发布失败抛业务异常（触发事务回滚）
        assertThrows(BizException.class, () ->
            service.create(new SkillSaveRequest("s1", "code-1", "正文", "desc", 1, List.of("local"))));
    }

    @Test
    void update_shouldRemoveCancelledTarget() {
        // 现有 skill 勾选 local+nacos，本次改为仅 local，应对 nacos 调 remove（用旧 skillCode）
        AiSkill existing = new AiSkill();
        existing.setId(9L);
        existing.setSkillCode("code-9");
        existing.setStorageTargets("local,nacos");
        when(skillMapper.selectById(9L)).thenReturn(existing);

        SkillContentPublisher local = publisher(SkillStorageTarget.LOCAL);
        SkillContentPublisher nacos = publisher(SkillStorageTarget.NACOS);
        SkillService service = serviceWith(local, nacos);

        service.update(9L, new SkillSaveRequest("s9", "code-9", "新正文", "desc", 1, List.of("local")));

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

        SkillContentPublisher local = publisher(SkillStorageTarget.LOCAL);
        SkillContentPublisher nacos = publisher(SkillStorageTarget.NACOS);
        doThrow(new RuntimeException("nacos down")).when(nacos).remove(any());
        SkillService service = serviceWith(local, nacos);

        // remove 失败仅记日志，不影响 update 成功
        service.update(9L, new SkillSaveRequest("s9", "code-9", "新正文", "desc", 1, List.of("local")));

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

        SkillContentPublisher local = publisher(SkillStorageTarget.LOCAL);
        doThrow(new RuntimeException("io error")).when(local).remove(any());
        SkillService service = serviceWith(local);

        // remove 失败不阻断删除
        service.delete(5L);

        verify(skillMapper).deleteById(5L);
        verify(local).remove("code-5");
    }

    private byte[] buildZip(String... nameContentPairs) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            for (int i = 0; i < nameContentPairs.length; i += 2) {
                zos.putNextEntry(new ZipEntry(nameContentPairs[i]));
                zos.write(nameContentPairs[i + 1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }
}
