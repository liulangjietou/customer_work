package com.richard.fyoung.customerwork.data.calllog;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentReplaySnapshotMigrationContractTest {

    @Test
    void flywayAndManualMirror_shouldStayIdenticalAndIdempotent() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        String migration = Files.readString(root.resolve(
            "customer-work-starter/src/main/resources/db/customerwork/migration/V19__agent_replay_snapshot.sql"));
        String mirror = Files.readString(root.resolve(
            "mysql/01-agent-scope-customer-work/customer-work-agent-replay-snapshot-alter.sql"));
        assertEquals(migration, mirror);
        assertTrue(migration.contains("information_schema.columns"));
        assertTrue(migration.contains("replay_snapshot_json"));
        assertTrue(migration.contains("JSON DEFAULT NULL"));
    }
}
