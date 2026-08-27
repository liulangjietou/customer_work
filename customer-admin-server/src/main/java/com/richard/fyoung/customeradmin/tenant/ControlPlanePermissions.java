package com.richard.fyoung.customeradmin.tenant;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 控制面专属权限策略。
 *
 * <p>控制面操作同时要求显式控制面角色与权限点。这里定义的是“普通租户角色不应被授予”的
 * 权限集合，供租户开通流程统一过滤；Controller 仍必须独立校验 {@link CrossTenantAuthority}，
 * 不能把角色授权配置当成安全边界。</p>
 *
 * <p><b>判定依据是“这个权限点碰的数据属于谁”</b>，不是菜单挂在哪一级。三类必须收归控制面：</p>
 * <ul>
 *   <li><b>操作的表不参与租户过滤</b>：{@code ai_model_config} 在
 *       {@code TenantInterceptors.TENANT_IGNORED_TABLES} 里，全平台只有一份。
 *       授给租户管理员等于让任何一个租户改掉或删掉所有租户在用的模型配置。</li>
 *   <li><b>能把代码或流量带出本进程</b>：MCP 可挂任意外部服务端，Skill 技能包含代码执行，
 *       内部工具箱的 HTTP 工具是现成的 SSRF 面，SQL 客户端能在已配数据源上跑任意语句。</li>
 *   <li><b>看到的是全平台而非本租户</b>：计费、SLO、死信队列、敏感词库与命中记录、
 *       限流规则、AI 编码审计。租户看到这些等于看到别人的经营数据。</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
public final class ControlPlanePermissions {

    /**
     * 平台运营专属权限族。
     *
     * <p>租户管理员拿到其中任何一个，影响面都会越出自己的租户边界。</p>
     */
    private static final Set<String> CONTROL_PLANE_FAMILIES = Set.of(
        // ---- 平台形态定义：租户只能使用，不能改写 ----
        "tenant",
        "menu",
        "login-image",
        "system-tool",
        "config-version",
        "dict",
        // ---- 操作的是租户忽略表（ai_model_config 全平台一份），见类注释第一类 ----
        "model",
        "model-experiment",
        // ---- 能把代码/流量带出本进程，见类注释第二类 ----
        "mcp",
        "skill",
        // ---- 视野是全平台而非本租户，见类注释第三类 ----
        "billing",
        "slo",
        "ai-audit",
        "dead-letter",
        "semantic-cache",
        "sensitive-word",
        "sensitive-hit-log",
        "rate-limit-rule",
        "governance",
        "improvement"
    );

    /**
     * 内部运维工具族：面向平台自己的工程师，任何租户都不该拥有。
     *
     * <p>与上面的区别在于额外一层处置——对外部署（{@code admin.public-deployment.enabled=true}）
     * 会连同菜单与接口一起下架，而不只是不授权。理由是这些能力的破坏面不取决于谁在用：
     * SQL 客户端能在已配数据源上执行任意语句，账号本行里直接存着目标站点的密码，
     * HTTP 工具可以拿服务端身份访问内网。留着只靠权限点挡，迟早有人配错角色。</p>
     */
    private static final Set<String> INTERNAL_TOOL_FAMILIES = Set.of(
        "workbench",
        "workbench-site",
        "devtools",
        "audit",
        "sql-console",
        "sql-config",
        "sql-datasource",
        "sql-define",
        "sql-query"
    );

    /** 族内多数权限点可授予、仅个别必须收归控制面时，走这份逐点清单。 */
    private static final Set<String> CONTROL_PLANE_CODES = Set.of(
        // 等级定义即额度上限本身。租户管理员能改等级 = 能自己给自己提额；
        // 按成员分配已有档位（subject-quota:user-edit）不越界，故只收这一个点。
        "subject-quota:level-edit"
    );

    /** 控制面专属族 + 内部工具族的并集，判定时逐族比对。 */
    private static final Set<String> RESTRICTED_FAMILIES;

    static {
        Set<String> families = new LinkedHashSet<>(CONTROL_PLANE_FAMILIES);
        families.addAll(INTERNAL_TOOL_FAMILIES);
        RESTRICTED_FAMILIES = Set.copyOf(families);
    }

    private ControlPlanePermissions() {
    }

    /** 判断权限点是否仅允许授予控制面角色。 */
    public static boolean isControlPlaneOnly(String permissionCode) {
        if (permissionCode == null || permissionCode.isBlank()) {
            return false;
        }
        if (CONTROL_PLANE_CODES.contains(permissionCode)) {
            return true;
        }
        return matchesFamily(permissionCode, RESTRICTED_FAMILIES);
    }

    /**
     * 判断权限点是否属于内部运维工具，对外部署时连菜单与接口一并下架。
     *
     * <p>内部工具必然也是控制面专属；反之不成立——模型配置是控制面专属，
     * 但它是平台运营每天都要用的能力，对外部署同样保留。</p>
     */
    public static boolean isInternalTool(String permissionCode) {
        if (permissionCode == null || permissionCode.isBlank()) {
            return false;
        }
        return matchesFamily(permissionCode, INTERNAL_TOOL_FAMILIES);
    }

    /** 权限点归属某一族：等于族名本身（一级菜单），或以“族名:”开头（族内具体操作）。 */
    private static boolean matchesFamily(String permissionCode, Set<String> families) {
        int separator = permissionCode.indexOf(':');
        String family = separator < 0 ? permissionCode : permissionCode.substring(0, separator);
        return families.contains(family);
    }

    /** 内部工具族只读视图，供对外部署的菜单过滤与接口拦截共用同一份事实。 */
    public static Set<String> internalToolFamilies() {
        return INTERNAL_TOOL_FAMILIES;
    }
}
