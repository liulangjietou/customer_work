package com.richard.fyoung.customeradmin.aiconfig.skill.storage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SftpSkillPublisher} 配置/路径逻辑单测（无本地 SFTP 服务，连接逻辑不强测）。
 * @author owlzhangfq@gmail.com
 */
class SftpSkillPublisherTest {

    @Test
    void target_shouldBeSftp() {
        assertEquals(SkillStorageTarget.SFTP, new SftpSkillPublisher(new SkillStorageProperties.Sftp()).target());
    }

    @Test
    void remoteSkillDir_shouldJoinRemoteDirAndSkillCode() {
        assertEquals("/data/skills/code-1", SftpSkillPublisher.remoteSkillDir("/data/skills", "code-1"));
    }

    @Test
    void remoteSkillDir_shouldTrimTrailingSlash() {
        assertEquals("/data/skills/code-1", SftpSkillPublisher.remoteSkillDir("/data/skills/", "code-1"));
    }

    @Test
    void remoteSkillFile_shouldAppendSkillMd() {
        assertEquals("/data/skills/code-1/SKILL.md", SftpSkillPublisher.remoteSkillFile("/data/skills", "code-1"));
    }

    @Test
    void cumulativePaths_shouldBuildIncrementalAbsolutePaths() {
        assertEquals(List.of("/data", "/data/skills", "/data/skills/code-1"),
            SftpSkillPublisher.cumulativePaths("/data/skills/code-1"));
    }

    @Test
    void cumulativePaths_shouldSupportRelativePaths() {
        assertEquals(List.of("data", "data/skills"),
            SftpSkillPublisher.cumulativePaths("data/skills"));
    }

    @Test
    void cumulativePaths_shouldSkipEmptySegments() {
        assertEquals(List.of("/a", "/a/b"), SftpSkillPublisher.cumulativePaths("/a//b/"));
    }
}
