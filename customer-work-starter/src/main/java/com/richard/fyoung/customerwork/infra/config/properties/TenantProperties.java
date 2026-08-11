package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多租户配置。默认关闭——单租户部署（升级前的既有形态）行为完全不变。
 *
 * <p>开启后 {@code TenantLineInnerInterceptor} 接管本 starter 独立 MyBatis 环境下的全部
 * SELECT/UPDATE/DELETE 改写与 INSERT 补列，且缺失租户上下文时 fail-closed 直接报错，
 * 而不是放行成全量查询——静默返回跨租户数据比报错危险得多。</p>
 */
@Data
public class TenantProperties {

    /** 是否开启多租户行级隔离。 */
    private boolean enabled = false;

    /** 租户列名（全部业务表统一）。 */
    private String columnName = "tenant_id";

    /**
     * 不参与租户过滤的表（平台级数据 + 框架自建表）。
     *
     * <p>清单语义见 {@code docs/多租户架构设计.md}：这里的表要么内容由平台定义租户只读，
     * 要么根本没有 {@code tenant_id} 列（框架自建），漏配会让 SQL 拼出不存在的列而报错。</p>
     */
    private List<String> ignoredTables = new ArrayList<>();

    /**
     * 是否在 starter 内开启 Reactor 自动上下文传播（{@code Hooks.enableAutomaticContextPropagation()}）。
     *
     * <p>WebFlux 主链路会切到 boundedElastic 执行阻塞 JDBC，而 MyBatis 拦截器读的是 ThreadLocal——
     * 不传播就必然在跨线程边界丢租户。该 Hook 是 JVM 全局的，故只在多租户开启时才装。</p>
     */
    private boolean autoContextPropagation = true;
}
