package com.richard.fyoung.customeradmin.aiconfig.model.mapper;

import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelCertification;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 认证晋级 SQL 必须原子校验当前配置，并拒绝更早启动的慢运行。 */
class ModelCertificationMapperSqlTest {

    private static final String RESOURCE = "mapper/AiModelCertificationMapper.xml";
    private static final String STATEMENT = AiModelCertificationMapper.class.getName()
        + ".promoteIfCurrent";

    @Test
    void promotion_shouldBindTenantEndpointSecretAndTimeOrderedAttempt() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = Resources.getResourceAsStream(RESOURCE)) {
            new XMLMapperBuilder(input, configuration, RESOURCE,
                configuration.getSqlFragments()).parse();
        }
        AiModelCertification certification = new AiModelCertification();
        certification.setModelConfigId(7L);
        certification.setTenantId("tenant-a");
        certification.setCurrentRunId(100L);
        certification.setCertifiedEndpointRevision(3);
        certification.setCertifiedSecretVersion(4);
        certification.setRevision(1);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("certification", certification);
        parameters.put("secretRefId", 20L);
        MappedStatement statement = configuration.getMappedStatement(STATEMENT);
        BoundSql boundSql = statement.getBoundSql(parameters);
        String sql = boundSql.getSql().replaceAll("\\s+", " ");

        assertTrue(sql.contains("model.tenant_id = ?"), sql);
        assertTrue(sql.contains("secret.tenant_id = model.tenant_id"), sql);
        assertTrue(sql.contains("model.endpoint_revision = ?"), sql);
        assertTrue(sql.contains("model.secret_ref_id <=> ?"), sql);
        assertTrue(sql.contains("secret.status = 'ACTIVE'"), sql);
        assertTrue(sql.contains("secret.current_version <=> ?"), sql);
        assertTrue(sql.contains(
            "VALUES(current_run_id) > ai_model_certification.current_run_id"), sql);
        assertTrue(sql.contains("ai_model_certification.tenant_id"), sql);
    }
}
