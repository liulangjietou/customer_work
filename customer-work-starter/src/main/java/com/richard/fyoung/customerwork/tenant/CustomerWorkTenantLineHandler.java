package com.richard.fyoung.customerwork.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * MyBatis-Plus 租户行级过滤策略：租户值取自 {@link TenantContext}，忽略表清单由装配方给定。
 *
 * <p>本类是"数据不串"的唯一强制点——SELECT/UPDATE/DELETE 自动带 {@code tenant_id} 条件，
 * INSERT 自动补列与值，业务代码一行不改。相应地，它必须 fail-closed：
 * {@link TenantContext#require()} 在缺上下文时抛出，让链路断在这里而不是悄悄查出别人的数据。</p>
 *
 * <p>不做成 Spring Bean、忽略表清单走构造参数，是为了让 admin 模块（排除了 starter 自动装配）
 * 能当普通构建器直接 new 出自己那套清单，与 {@code AdminOtelTracingConfig} 复用 starter 构建器的做法一致。</p>
 * @author owlzhangfq@gmail.com
 */
public class CustomerWorkTenantLineHandler implements TenantLineHandler {

    private final String columnName;

    /** 统一转小写存放：MySQL 表名大小写敏感性依赖服务器配置，比对时不受其影响。 */
    private final Set<String> ignoredTables;

    public CustomerWorkTenantLineHandler(String columnName, Collection<String> ignoredTables) {
        this.columnName = columnName;
        this.ignoredTables = new HashSet<>();
        if (ignoredTables != null) {
            for (String table : ignoredTables) {
                if (table != null && !table.isBlank()) {
                    this.ignoredTables.add(table.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    @Override
    public Expression getTenantId() {
        return new StringValue(TenantContext.require());
    }

    @Override
    public String getTenantIdColumn() {
        return columnName;
    }

    @Override
    public boolean ignoreTable(String tableName) {
        return tableName != null && ignoredTables.contains(tableName.trim().toLowerCase(Locale.ROOT));
    }
}
