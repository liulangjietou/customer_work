package com.richard.fyoung.customeradmin.aiconfig.skill.storage;

import com.richard.fyoung.customerwork.data.skill.storage.MinioSkillPublisher;
import com.richard.fyoung.customerwork.data.skill.storage.SkillContentPublisher;
import com.richard.fyoung.customerwork.data.skill.storage.SkillStorageSettings;
import com.richard.fyoung.customerwork.data.skill.storage.SkillStorageTarget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillStorageConfig} 单测：admin 配置 → starter 入参的字段映射不能漏（漏一个字段就是静默用默认值），
 * 以及 Bean 方法按目标建出正确实现。
 * @author owlzhangfq@gmail.com
 */
class SkillStorageConfigTest {

    @Test
    void toSettings_shouldMapAllConnectionFields() {
        SkillStorageProperties properties = new SkillStorageProperties();
        SkillStorageProperties.Minio minio = properties.getMinio();
        minio.setEndpoint("http://minio-host:9000");
        minio.setAccessKey("ak");
        minio.setSecretKey("sk");
        minio.setBucket("bkt");
        minio.setPrefix("p/");
        SkillStorageProperties.Nacos nacos = properties.getNacos();
        nacos.setServerAddr("nacos-host:8848");
        nacos.setNamespace("ns-1");
        nacos.setGroup("G1");
        nacos.setUsername("u1");
        nacos.setPassword("p1");
        nacos.setDataIdPrefix("sk-");
        nacos.setTimeoutMs(5000);
        SkillStorageProperties.Sftp sftp = properties.getSftp();
        sftp.setHost("sftp-host");
        sftp.setPort(2222);
        sftp.setUsername("u2");
        sftp.setPassword("p2");
        sftp.setRemoteDir("/data/skills");
        sftp.setTimeoutMs(8000);
        sftp.setStrictHostKeyChecking(true);

        SkillStorageSettings settings = SkillStorageConfig.toSettings(properties);

        assertEquals("http://minio-host:9000", settings.getMinio().getEndpoint());
        assertEquals("ak", settings.getMinio().getAccessKey());
        assertEquals("sk", settings.getMinio().getSecretKey());
        assertEquals("bkt", settings.getMinio().getBucket());
        assertEquals("p/", settings.getMinio().getPrefix());
        assertEquals("nacos-host:8848", settings.getNacos().getServerAddr());
        assertEquals("ns-1", settings.getNacos().getNamespace());
        assertEquals("G1", settings.getNacos().getGroup());
        assertEquals("u1", settings.getNacos().getUsername());
        assertEquals("p1", settings.getNacos().getPassword());
        assertEquals("sk-", settings.getNacos().getDataIdPrefix());
        assertEquals(5000, settings.getNacos().getTimeoutMs());
        assertEquals("sftp-host", settings.getSftp().getHost());
        assertEquals(2222, settings.getSftp().getPort());
        assertEquals("u2", settings.getSftp().getUsername());
        assertEquals("p2", settings.getSftp().getPassword());
        assertEquals("/data/skills", settings.getSftp().getRemoteDir());
        assertEquals(8000, settings.getSftp().getTimeoutMs());
        assertTrue(settings.getSftp().isStrictHostKeyChecking());
    }

    @Test
    void minioSkillPublisher_shouldBeMinioImplementation() {
        SkillContentPublisher publisher =
            new SkillStorageConfig().minioSkillPublisher(new SkillStorageProperties());

        assertInstanceOf(MinioSkillPublisher.class, publisher);
        assertEquals(SkillStorageTarget.MINIO, publisher.target());
    }
}
