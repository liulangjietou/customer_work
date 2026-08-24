package com.richard.fyoung.customerwork.safety.tenant;

import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 租户行级过滤拦截器的构建器（starter 与 admin 共用一套忽略表口径）。
 *
 * <p>两个模块各有独立的 MyBatis 环境，但"哪些表不参与租户过滤"必须是同一份答案——
 * 分成两份迟早会漂移，而漂移的后果是某一侧漏过滤（串数据）或多过滤（SQL 报不存在的列）。</p>
 * @author owlzhangfq@gmail.com
 */
public final class TenantInterceptors {

    /**
     * 内置忽略表：全局数据 + 框架自建表。判定依据与新增规则见 {@code docs/多租户架构设计.md} §2.2。
     *
     * <p><b>加表进这份清单等于放弃该表的自动隔离</b>，务必确认它属于下面<b>四类</b>之一：</p>
     * <ol>
     *   <li><b>内容由系统定义、租户只读</b>：{@code sys_permission} 权限点定义、
     *       {@code ai_system_tool} 代码级工具目录、{@code ai_model_price} 系统统一定价、
     *       {@code login_carousel_image}（登录前匿名访问，此刻没有任何租户上下文）；</li>
     *   <li><b>框架自建、无法加列</b>：{@code ai_chat_session_state} 由 MysqlAgentStateStore
     *       直接持 DataSource 读写；归属另记在 {@code ai_workspace_session}，靠 JOIN 补回隔离；</li>
     *   <li><b>需要两级可见性，由统一入口手工过滤</b>：{@code ai_model_config} 承载模型凭据，
     *       读取时 {@code tenant_id IN (当前租户, 'default')}、默认共享记录对其它租户视角不回显凭据、
     *       写入/删除强制校验归属。<b>管理面补偿实现在 {@code ModelConfigService}，运行时读取补偿实现在
     *       {@code ModelConfigAccess}</b>；业务代码不得直接调用 Mapper（该表也刻意不在
     *       {@code DataScopeTables} 白名单里）；</li>
     *   <li><b>带 tenant_id 但刻意不自动过滤，访问控制在 Controller</b>：{@code sys_tenant_quota}——
     *       控制面要跨租户配额度，而租户管理员本就不该看到自己的额度设置。
     *       <b>给这张表新写任何查询接口都必须显式加跨租户权限校验</b>，别处不设防。</li>
     * </ol>
     *
     * <p>第 3、4 类的补偿控制在别处，删掉那边的实现不会让本文件报错——
     * 因此它们各自都有专门的回归测试盯着，改动前先看测试。</p>
     */
    public static final List<String> TENANT_IGNORED_TABLES = List.of(
        "sys_tenant",
        "sys_permission",
        "sys_menu_change_log",
        "ai_system_tool",
        "ai_chat_session_state",
        // 登录页是系统统一入口（sys_user.username 全局唯一，全系统只有一个登录页），
        // /api/login-images/** 在登录前匿名访问，此刻没有任何租户上下文——加列参与过滤
        // 只会让登录页 fail-closed 打不开。与 sys_permission 同属"系统定义、租户只读"
        "login_carousel_image",
        "ai_model_config",
        // 单价由控制面统一定义，租户不该也不能自己定价，故无 tenant_id 列
        "ai_model_price",
        // 日账单按自然日串行重建的内部锁，无业务数据且无 tenant_id
        "cw_usage_aggregation_lock",
        // 配额带 tenant_id 但刻意不自动过滤：控制面要跨租户配额度，而租户管理员
        // 本就不该看到自己的额度设置。访问控制由 Controller 的跨租户权限校验负责
        "sys_tenant_quota",
        "flyway_schema_history");

    private TenantInterceptors() {
    }

    /**
     * 构建拦截器：内置租户忽略表清单 + 调用方追加的忽略表。
     *
     * @param columnName    租户列名
     * @param extraIgnored  额外忽略表（可为空），用于宿主自有表的按需豁免
     */
    public static TenantLineInnerInterceptor build(String columnName, Collection<String> extraIgnored) {
        Set<String> ignored = new LinkedHashSet<>(TENANT_IGNORED_TABLES);
        if (extraIgnored != null) {
            ignored.addAll(extraIgnored);
        }
        return new TenantLineInnerInterceptor(new CustomerWorkTenantLineHandler(columnName, ignored));
    }

    /** 便捷重载：默认列名 {@code tenant_id}，无额外忽略表。 */
    public static TenantLineInnerInterceptor build(String... extraIgnored) {
        return build("tenant_id", Arrays.asList(extraIgnored));
    }
}
