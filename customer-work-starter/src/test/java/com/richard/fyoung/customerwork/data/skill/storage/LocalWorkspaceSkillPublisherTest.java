package com.richard.fyoung.customerwork.data.skill.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LocalWorkspaceSkillPublisher} 真实读写单测：发布 / 覆盖 / 移除。
 * @author owlzhangfq@gmail.com
 */
class LocalWorkspaceSkillPublisherTest {

    @TempDir
    Path baseDir;

    @Test
    void target_shouldBeLocal() {
        assertEquals(SkillStorageTarget.LOCAL, newPublisher().target());
    }

    @Test
    void publish_shouldWriteSkillMdUnderSkillCodeDir() throws IOException {
        newPublisher().publish("demo-skill", "hello skill");

        Path file = baseDir.resolve("demo-skill").resolve("SKILL.md");
        assertTrue(Files.exists(file));
        assertEquals("hello skill", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void publish_shouldOverwriteExistingContent() throws IOException {
        LocalWorkspaceSkillPublisher publisher = newPublisher();
        publisher.publish("demo-skill", "v1");
        publisher.publish("demo-skill", "v2");

        Path file = baseDir.resolve("demo-skill").resolve("SKILL.md");
        assertEquals("v2", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void publishFiles_shouldWriteFilesWithSubDirs() throws IOException {
        LocalWorkspaceSkillPublisher publisher = newPublisher();
        publisher.publish("demo-skill", "content");
        byte[] binary = {0x00, (byte) 0xFF, 0x10};

        publisher.publishFiles("demo-skill", java.util.List.of(
            new SkillFileContent("references/doc.md", "参考".getBytes(StandardCharsets.UTF_8)),
            new SkillFileContent("assets/logo.bin", binary)));

        assertEquals("参考", Files.readString(baseDir.resolve("demo-skill/references/doc.md"), StandardCharsets.UTF_8));
        org.junit.jupiter.api.Assertions.assertArrayEquals(binary,
            Files.readAllBytes(baseDir.resolve("demo-skill/assets/logo.bin")));
    }

    @Test
    void remove_shouldDeleteSkillDir_includingNestedFiles() {
        LocalWorkspaceSkillPublisher publisher = newPublisher();
        publisher.publish("demo-skill", "content");
        publisher.publishFiles("demo-skill", java.util.List.of(
            new SkillFileContent("references/doc.md", "参考".getBytes(StandardCharsets.UTF_8))));

        publisher.remove("demo-skill");

        assertFalse(Files.exists(baseDir.resolve("demo-skill")));
    }

    @Test
    void remove_shouldBeNoOp_whenSkillDirAbsent() {
        // 目标目录不存在时移除不应抛异常
        newPublisher().remove("never-created");
    }

    private LocalWorkspaceSkillPublisher newPublisher() {
        return new LocalWorkspaceSkillPublisher(baseDir.toString());
    }
}
