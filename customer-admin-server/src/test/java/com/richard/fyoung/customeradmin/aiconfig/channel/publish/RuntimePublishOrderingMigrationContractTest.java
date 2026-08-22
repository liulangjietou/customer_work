package com.richard.fyoung.customeradmin.aiconfig.channel.publish;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePublishOrderingMigrationContractTest {

    private static final Path FLYWAY = Path.of(
        "src/main/resources/db/migration/V80__runtime_publish_nacos_key_index.sql");
    private static final Path MIRROR = Path.of(
        "../mysql/02-customer-admin/80-V80__runtime_publish_nacos_key_index.sql");

    @Test
    void migration_shouldKeepMirrorAndRealNacosKeyIndexContract() throws Exception {
        String sql = Files.readString(FLYWAY, StandardCharsets.UTF_8);
        String mirror = Files.readString(MIRROR, StandardCharsets.UTF_8);

        assertEquals(sql, mirror, "Flyway 迁移与 DBA 镜像必须逐字一致");
        assertTrue(sql.contains("ADD KEY `idx_runtime_publish_nacos_key` "
            + "(`tenant_id`, `data_id`, `group_name`, `seq`, `status`)"));
        assertFalse(sql.contains("DROP INDEX"), "保留既有 target 查询索引");
    }
}
