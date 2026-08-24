package com.richard.fyoung.customeradmin.workspace.callstats;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentReplayMigrationContractTest {

    @Test
    void flywayAndDbaMirror_shouldStayIdenticalAndSeedSeparatePermission() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        String migration = Files.readString(root.resolve(
            "customer-admin-server/src/main/resources/db/migration/V93__agent_replay_snapshot.sql"));
        String mirror = Files.readString(root.resolve(
            "mysql/02-customer-admin/93-V93__agent_replay_snapshot.sql"));
        assertEquals(migration, mirror);
        assertTrue(migration.contains("replay_snapshot_json"));
        assertTrue(migration.contains("agent-call-stats:replay"));
        assertTrue(migration.contains("NOT EXISTS"));
    }
}
