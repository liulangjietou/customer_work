package com.richard.fyoung.customeradmin.aiconfig.model.mapper;

import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.richard.fyoung.customerwork.safety.tenant.CustomerWorkTenantLineHandler;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 引用投影 SQL 的 XML 装配、主/备用/实验 UNION 与租户拦截器兼容性测试。 */
class ModelAgentReferenceMapperSqlTest {

    private static final String RESOURCE = "mapper/ModelAgentReferenceMapper.xml";
    private static final String STATEMENT = ModelAgentReferenceMapper.class.getName() + ".findReferences";

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void privateModelQuery_shouldKeepExplicitScopeAndAcceptTenantRewrite() throws Exception {
        Configuration configuration = configuration();
        MappedStatement statement = configuration.getMappedStatement(STATEMENT);
        BoundSql boundSql = statement.getBoundSql(Map.of("modelId", 7L, "tenantId", "tenant-a"));
        String rawSql = boundSql.getSql();
        assertTrue(rawSql.contains("UNION"));
        assertTrue(rawSql.contains("agent.tenant_id = ?"));
        assertTrue(rawSql.contains("ai_model_experiment"));
        assertTrue(rawSql.contains("experiment.control_deployment_id = ?"));
        assertTrue(rawSql.contains("experiment.treatment_deployment_id = ?"));
        assertTrue(rawSql.contains("experiment.status IN ('DRAFT', 'RUNNING')"));
        assertTrue(rawSql.contains("experiment.status IN ('STOPPED', 'COMPLETED')"));
        assertTrue(rawSql.contains("deactivation.tenant_id = experiment.tenant_id"));
        assertTrue(rawSql.contains("deactivation.experiment_publish_action = 'DEACTIVATE'"));
        assertTrue(rawSql.contains("deactivation.status <> 'APPLIED'"));
        assertTrue(rawSql.contains("experiment.tenant_id = ?"));

        TenantContext.set("tenant-a");
        TenantLineInnerInterceptor interceptor = new TenantLineInnerInterceptor(
            new CustomerWorkTenantLineHandler("tenant_id", List.of()));
        interceptor.beforeQuery(null, statement, null, RowBounds.DEFAULT, null, boundSql);

        String rewrittenSql = boundSql.getSql();
        assertTrue(rewrittenSql.contains("agent.tenant_id = 'tenant-a'"), rewrittenSql);
        assertTrue(rewrittenSql.contains("backup.tenant_id = 'tenant-a'"), rewrittenSql);
        assertTrue(rewrittenSql.contains("experiment.tenant_id = 'tenant-a'"), rewrittenSql);
    }

    @Test
    void sharedModelQuery_shouldScanTenantsOnlyThroughExplicitCrossTenantBoundary() throws Exception {
        MappedStatement statement = configuration().getMappedStatement(STATEMENT);

        String sql = statement.getBoundSql(Map.of("modelId", 7L)).getSql().replaceAll("\\s+", " ");

        assertFalse(sql.contains("experiment.tenant_id = ?"));
        assertTrue(sql.contains("agent.tenant_id = experiment.tenant_id"));
        assertTrue(sql.contains("deactivation.tenant_id = experiment.tenant_id"));
    }

    private Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = Resources.getResourceAsStream(RESOURCE)) {
            new XMLMapperBuilder(input, configuration, RESOURCE, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }
}
