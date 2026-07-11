package com.richard.fyoung.customeradmin.aiconfig.skill.service;

import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentSkillMapper;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * {@link SkillService#parseUploadContent} 单测：.md 直接读正文，.zip 找 SKILL.md（任意目录层级、
 * 大小写不敏感），找不到/文件类型不支持时报业务错误。
 * @author owlzhangfq@gmail.com
 */
class SkillServiceTest {

    private SkillService service;

    @BeforeEach
    void setUp() {
        AiSkillMapper skillMapper = mock(AiSkillMapper.class);
        AiAgentSkillMapper agentSkillMapper = mock(AiAgentSkillMapper.class);
        AiAgentMapper agentMapper = mock(AiAgentMapper.class);
        AgentInstanceCache agentInstanceCache = mock(AgentInstanceCache.class);
        service = new SkillService(skillMapper, agentSkillMapper, agentMapper, agentInstanceCache);
    }

    @Test
    void parseUploadContent_shouldReadMdFileDirectly() {
        String markdown = "---\nname: test-skill\n---\n\n技能正文";
        MockMultipartFile file = new MockMultipartFile("file", "SKILL.md", "text/markdown",
            markdown.getBytes(StandardCharsets.UTF_8));

        String result = service.parseUploadContent(file);

        assertEquals(markdown, result);
    }

    @Test
    void parseUploadContent_shouldExtractSkillMdFromZip_atNestedPath() throws IOException {
        String markdown = "---\nname: zipped-skill\n---\n\n压缩包里的技能正文";
        byte[] zipBytes = buildZip("my-skill/SKILL.md", markdown, "my-skill/references/doc.txt", "参考资料");
        MockMultipartFile file = new MockMultipartFile("file", "my-skill.zip", "application/zip", zipBytes);

        String result = service.parseUploadContent(file);

        assertEquals(markdown, result);
    }

    @Test
    void parseUploadContent_shouldMatchSkillMdCaseInsensitively() throws IOException {
        String markdown = "内容";
        byte[] zipBytes = buildZip("skill.md", markdown);
        MockMultipartFile file = new MockMultipartFile("file", "a.zip", "application/zip", zipBytes);

        String result = service.parseUploadContent(file);

        assertEquals(markdown, result);
    }

    @Test
    void parseUploadContent_shouldRejectZipWithoutSkillMd() throws IOException {
        byte[] zipBytes = buildZip("readme.txt", "无关内容");
        MockMultipartFile file = new MockMultipartFile("file", "a.zip", "application/zip", zipBytes);

        assertThrows(BizException.class, () -> service.parseUploadContent(file));
    }

    @Test
    void parseUploadContent_shouldRejectUnsupportedExtension() {
        MockMultipartFile file = new MockMultipartFile("file", "skill.txt", "text/plain", "内容".getBytes(StandardCharsets.UTF_8));

        assertThrows(BizException.class, () -> service.parseUploadContent(file));
    }

    @Test
    void parseUploadContent_shouldRejectEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "skill.md", "text/markdown", new byte[0]);

        assertThrows(BizException.class, () -> service.parseUploadContent(file));
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
