package com.richard.fyoung.customeradmin.tenant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制面权限判定。
 *
 * <p>这份断言是"哪些能力不能落到租户手里"的机器可读版本。新增权限族时，
 * 先在这里回答一个问题：<b>租户管理员拿到它，影响面会不会越出自己的租户</b>。
 * 会，就进控制面清单并在此加断言；不会，就加进第二个用例的可授予列表。</p>
 */
class ControlPlanePermissionsTest {

    @Test
    void shouldRecognizeControlPlanePermissionFamiliesAndExactCodes() {
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("tenant:view"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("menu"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("login-image:edit"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("system-tool:view"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("config-version:rollback"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("dict:add"));
    }

    /**
     * {@code ai_model_config} 在 {@code TenantInterceptors.TENANT_IGNORED_TABLES} 里，
     * 全平台只有一份。租户管理员一旦拿到 model 族，改的、删的都是所有租户在用的那份配置。
     */
    @Test
    void shouldProtectGloballySharedModelConfiguration() {
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("model"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("model:view"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("model:edit"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("model:delete"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("model-experiment:create"));
    }

    /** MCP 可挂任意外部服务端，Skill 技能包含代码执行——都能把代码或流量带出本进程。 */
    @Test
    void shouldProtectCodeAndTrafficEgressCapabilities() {
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("mcp:add"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("skill:add"));
    }

    /** 这几族看到的是全平台数据，租户看到它们等于看到别人的经营情况。 */
    @Test
    void shouldProtectPlatformWideVisibility() {
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("billing:view"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("billing:quota-edit"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("slo:view"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("ai-audit:view"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("dead-letter:view"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("semantic-cache:view"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("sensitive-word:view"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("sensitive-word:add"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("sensitive-hit-log:view"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("rate-limit-rule:edit"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("governance:approve"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("improvement:manage"));
    }

    /**
     * 内部运维工具既是控制面专属，也是对外部署要整体下架的那一批。
     *
     * <p>两个判定必须同时成立：只判前者，对外实例上超管仍能看到 SQL 客户端的菜单；
     * 只判后者，内网实例的租户管理员会拿到能跑任意 SQL 的权限。</p>
     */
    @Test
    void shouldTreatInternalToolsAsBothControlPlaneAndPublicDeploymentDisabled() {
        for (String code : new String[] {"sql-console:query", "sql-datasource:add", "sql-define:edit",
            "sql-query:view", "sql-config", "workbench", "workbench-site:view", "devtools", "audit:view"}) {
            assertTrue(ControlPlanePermissions.isControlPlaneOnly(code), code + " 应为控制面专属");
            assertTrue(ControlPlanePermissions.isInternalTool(code), code + " 应在对外部署下架");
        }
    }

    /** 控制面专属不等于内部工具：模型配置是平台运营每天都用的能力，对外部署同样保留。 */
    @Test
    void shouldNotTakeDownPlatformOperationCapabilitiesOnPublicDeployment() {
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("model:edit"));
        assertFalse(ControlPlanePermissions.isInternalTool("model:edit"));
        assertFalse(ControlPlanePermissions.isInternalTool("tenant:view"));
        assertFalse(ControlPlanePermissions.isInternalTool("billing:view"));
    }

    /**
     * 等级定义即额度上限本身，租户管理员能改等级就等于能自己给自己提额；
     * 而按成员分配已有档位不越界，仍留给租户。
     */
    @Test
    void shouldProtectQuotaLevelDefinitionButKeepPerUserAssignment() {
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("subject-quota:level-edit"));
        assertFalse(ControlPlanePermissions.isControlPlaneOnly("subject-quota:view"));
        assertFalse(ControlPlanePermissions.isControlPlaneOnly("subject-quota:user-edit"));
    }

    @Test
    void shouldKeepTenantSelfServicePermissionsGrantable() {
        assertFalse(ControlPlanePermissions.isControlPlaneOnly("role:edit"));
        assertFalse(ControlPlanePermissions.isControlPlaneOnly("user:add"));
        assertFalse(ControlPlanePermissions.isControlPlaneOnly("agent:edit"));
        assertFalse(ControlPlanePermissions.isControlPlaneOnly("knowledge-base:view"));
        assertFalse(ControlPlanePermissions.isControlPlaneOnly("channel-robot:view"));
        assertFalse(ControlPlanePermissions.isControlPlaneOnly("csat:view"));
        assertFalse(ControlPlanePermissions.isControlPlaneOnly("eval:view"));
        assertFalse(ControlPlanePermissions.isControlPlaneOnly("user-ticket:view"));
        assertFalse(ControlPlanePermissions.isControlPlaneOnly(null));
        assertFalse(ControlPlanePermissions.isControlPlaneOnly(""));
        assertFalse(ControlPlanePermissions.isInternalTool(null));
        assertFalse(ControlPlanePermissions.isInternalTool(""));
    }

    /**
     * 前缀匹配必须按族名整体比对，不能用 {@code startsWith}。
     *
     * <p>{@code startsWith("model")} 会把假想中的 {@code modelica:view} 一并收走；
     * 反过来 {@code startsWith("audit")} 会误伤 {@code ai-audit} 之外的同前缀新族。</p>
     */
    @Test
    void shouldMatchFamilyBoundaryNotPrefix() {
        assertFalse(ControlPlanePermissions.isControlPlaneOnly("modelica:view"));
        assertFalse(ControlPlanePermissions.isControlPlaneOnly("skillset:view"));
        assertFalse(ControlPlanePermissions.isInternalTool("workbenchx:view"));
    }
}
