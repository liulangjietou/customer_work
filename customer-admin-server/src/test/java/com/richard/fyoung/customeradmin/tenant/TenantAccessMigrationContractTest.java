package com.richard.fyoung.customeradmin.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantAccessMigrationContractTest {

    @Test
    void v65_shouldAddEpochsAndReliableTaskWithoutAllocatingPermissions() throws Exception {
        String sql;
        try (var input = new ClassPathResource(
            "db/migration/V65__tenant_access_revocation.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String mirror = Files.readString(repositoryRoot().resolve(
            "mysql/02-customer-admin/65-V65__tenant_access_revocation.sql"));

        assertEquals(sql, mirror, "Flyway 迁移与 DBA 镜像必须逐字一致");
        assertTrue(sql.contains("`auth_epoch` BIGINT NOT NULL DEFAULT 0"));
        assertTrue(sql.contains("`access_epoch` BIGINT NOT NULL DEFAULT 0"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `sys_tenant_access_publish_task`"));
        assertTrue(sql.contains("`operation` VARCHAR(24) NOT NULL"));
        assertTrue(sql.contains("`session_revocation_status` VARCHAR(24) NOT NULL"));
        assertTrue(sql.contains("`channel_disable_status` VARCHAR(24) NOT NULL"));
        assertTrue(sql.contains("UNIQUE KEY `uk_tenant_access_publish_active_lease`"));
        assertFalse(sql.contains("INSERT INTO `sys_permission`"), "本批次复用 tenant 权限，不占用 248+ ID");
    }

    private Path repositoryRoot() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        return Files.isDirectory(workingDirectory.resolve("mysql"))
            ? workingDirectory : workingDirectory.getParent();
    }
}
