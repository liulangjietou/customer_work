package com.richard.fyoung.customerwork.skill.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * {@link SkillContentPublishers} 选型单测：三个目标各建对应实现，构造不触发外部连接。
 * @author owlzhangfq@gmail.com
 */
class SkillContentPublishersTest {

    private final SkillStorageSettings settings = new SkillStorageSettings();

    @Test
    void create_shouldReturnLocalPublisher_forLocalTarget() {
        SkillContentPublisher publisher = SkillContentPublishers.create(SkillStorageTarget.LOCAL, settings);

        assertInstanceOf(LocalWorkspaceSkillPublisher.class, publisher);
        assertEquals(SkillStorageTarget.LOCAL, publisher.target());
    }

    @Test
    void create_shouldReturnNacosPublisher_forNacosTarget() {
        SkillContentPublisher publisher = SkillContentPublishers.create(SkillStorageTarget.NACOS, settings);

        assertInstanceOf(NacosSkillPublisher.class, publisher);
        assertEquals(SkillStorageTarget.NACOS, publisher.target());
    }

    @Test
    void create_shouldReturnSftpPublisher_forSftpTarget() {
        SkillContentPublisher publisher = SkillContentPublishers.create(SkillStorageTarget.SFTP, settings);

        assertInstanceOf(SftpSkillPublisher.class, publisher);
        assertEquals(SkillStorageTarget.SFTP, publisher.target());
    }
}
