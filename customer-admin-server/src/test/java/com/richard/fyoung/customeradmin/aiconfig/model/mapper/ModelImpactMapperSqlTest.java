package com.richard.fyoung.customeradmin.aiconfig.model.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 影响图 SQL 的资源覆盖、租户参数和 XML 装配契约。 */
class ModelImpactMapperSqlTest {

    private static final String RESOURCE = "mapper/ModelImpactMapper.xml";
    private static final String STATEMENT = ModelImpactMapper.class.getName() + ".findImpacts";

    @Test
    void privateScope_shouldCoverPrimaryBackupExperimentChannelTaskVersionRouteAndDeliveryReferences() throws Exception {
        String sql = boundSql(Map.of("modelId", 7L, "tenantId", "tenant-a"));

        assertTrue(sql.contains("agent.model_id = ?"));
        assertTrue(sql.contains("ai_agent_backup_model"));
        assertTrue(sql.contains("ai_channel_binding"));
        assertTrue(sql.contains("ai_channel_robot"));
        assertTrue(sql.contains("ai_scheduled_task"));
        assertTrue(sql.contains("ai_config_version"));
        assertTrue(sql.contains("ai_model_experiment"));
        assertTrue(sql.contains("experiment.control_deployment_id = ?"));
        assertTrue(sql.contains("experiment.treatment_deployment_id = ?"));
        assertTrue(sql.contains("experiment.status IN ('DRAFT', 'RUNNING')"));
        assertTrue(sql.contains("experiment.status IN ('STOPPED', 'COMPLETED')"));
        assertTrue(sql.contains("activation.tenant_id = experiment.tenant_id"));
        assertTrue(sql.contains("activation.experiment_publish_action = 'ACTIVATE'"));
        assertTrue(sql.contains("deactivation.tenant_id = experiment.tenant_id"));
        assertTrue(sql.contains("deactivation.experiment_publish_action = 'DEACTIVATE'"));
        assertTrue(sql.contains("deactivation.status &lt;&gt; 'APPLIED'")
            || sql.contains("deactivation.status <> 'APPLIED'"));
        assertTrue(sql.contains("CASE WHEN experiment.status = 'DRAFT' THEN 0 ELSE 1 END"));
        assertTrue(sql.contains("RUNNING/ACTIVATING"));
        assertTrue(sql.contains("DEACTIVATION_FAILED"));
        assertTrue(sql.contains("experiment.tenant_id = ?"));
        assertTrue(sql.contains("ai_model_route_rule"));
        assertTrue(sql.contains("ai_model_route_policy_version"));
        assertTrue(sql.contains("ai_model_route_policy"));
        assertTrue(sql.contains("ai_runtime_publish_task"));
        assertTrue(sql.contains("agent.tenant_id = ?"));
        assertTrue(sql.contains("version.tenant_id = ?"));
    }

    @Test
    void sharedControlPlaneScope_shouldIntentionallyScanAllTenants() throws Exception {
        String sql = boundSql(Map.of("modelId", 7L));

        assertFalse(sql.contains("agent.tenant_id = ?"));
        assertFalse(sql.contains("version.tenant_id = ?"));
        assertFalse(sql.contains("experiment.tenant_id = ?"));
        assertTrue(sql.contains("model_agents"));
        assertTrue(sql.contains("rule.deployment_id = ?"));
        assertTrue(sql.contains("experiment.control_deployment_id = ?"));
        assertTrue(sql.contains("experiment.treatment_deployment_id = ?"));
    }

    private String boundSql(Map<String, Object> parameters) throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = Resources.getResourceAsStream(RESOURCE)) {
            new XMLMapperBuilder(input, configuration, RESOURCE, configuration.getSqlFragments()).parse();
        }
        MappedStatement statement = configuration.getMappedStatement(STATEMENT);
        BoundSql boundSql = statement.getBoundSql(parameters);
        return boundSql.getSql().replaceAll("\\s+", " ");
    }
}
