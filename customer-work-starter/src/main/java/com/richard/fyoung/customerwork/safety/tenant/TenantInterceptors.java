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
     * 内置忽略表：平台级数据 + 框架自建表。判定依据与新增规则见 {@code docs/多租户架构设计.md}。
     *
     * <p>加表进这份清单等于放弃该表的自动隔离，务必确认它属于下面三类之一：
     * 内容由平台定义租户只读（{@code sys_permission} 权限点定义、{@code ai_system_tool} 代码级工具目录）、
     * 框架自建无法加列（{@code ai_chat_session_state} 由 MysqlAgentStateStore 直接持 DataSource 读写）、
     * 需要两级可见性因而由 Service 层手工过滤（{@code ai_model_config} 承载模型凭据）。</p>
     */
    public static final List<String> PLATFORM_LEVEL_TABLES = List.of(
        "sys_tenant",
        "sys_permission",
        "sys_menu_change_log",
        "ai_system_tool",
        "ai_chat_session_state",
        // 登录页是平台统一入口（sys_user.username 全局唯一，全系统只有一个登录页），
        // /api/login-images/** 在登录前匿名访问，此刻没有任何租户上下文——加列参与过滤
        // 只会让登录页 fail-closed 打不开。与 sys_permission 同属"平台定义、租户只读"
        "login_carousel_image",
        "ai_model_config",
        // 单价由平台统一定义，租户不该也不能自己定价，故无 tenant_id 列
        "ai_model_price",
        // 配额带 tenant_id 但刻意不自动过滤：运营方要跨租户配额度，而租户管理员
        // 本就不该看到自己的额度设置。访问控制由 Controller 的运营方校验负责
        "sys_tenant_quota",
        "flyway_schema_history");

    private TenantInterceptors() {
    }

    /**
     * 构建拦截器：内置平台级表清单 + 调用方追加的忽略表。
     *
     * @param columnName    租户列名
     * @param extraIgnored  额外忽略表（可为空），用于宿主自有表的按需豁免
     */
    public static TenantLineInnerInterceptor build(String columnName, Collection<String> extraIgnored) {
        Set<String> ignored = new LinkedHashSet<>(PLATFORM_LEVEL_TABLES);
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
