package com.richard.fyoung.customeradmin.datascope;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.core.toolkit.StringPool;
import com.baomidou.mybatisplus.extension.plugins.inner.BaseMultiTableInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.sql.Connection;
import java.util.List;

/**
 * 用户维度的行级过滤：给白名单内的表自动追加 {@code 归属列 = 当前用户 OR 归属列 IS NULL}。
 *
 * <p>与租户拦截器并列的第二道过滤，两者叠加得到"当前租户 + 本人"。复用 MyBatis-Plus 的
 * {@link BaseMultiTableInnerInterceptor}——它已经把 JOIN、子查询、UNION、WITH 里每一处表引用
 * 都遍历到位，自己解析 SQL 只会漏掉这些角落。</p>
 *
 * <p><b>三个刻意的取舍</b>：</p>
 * <ul>
 *   <li><b>不处理 INSERT</b>。归属列由 {@code MyMetaObjectHandler} 在字段填充阶段写入，
 *       那里能拿到完整的登录态；拦截器在 SQL 层再补一次，两处都写迟早对不上。</li>
 *   <li><b>处理 UPDATE / DELETE</b>，不只是 SELECT。只过滤列表页而不管写操作的话，
 *       用户虽然看不到别人的行，却能凭 ID 直接改删——{@code updateById} 只按主键定位，
 *       这是实打实的越权。加上条件后越权写影响 0 行，静默失败正是期望行为。</li>
 *   <li><b>{@code IS NULL} 视为租户内共享</b>。存量数据没有归属人，若一并挡掉，
 *       升级当天所有历史记录会从页面上消失；而新产生的数据一律带归属人，随时间自然收敛。</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
@SuppressWarnings({"rawtypes"})
public class DataScopeInnerInterceptor extends BaseMultiTableInnerInterceptor implements InnerInterceptor {

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter, RowBounds rowBounds,
                            ResultHandler resultHandler, BoundSql boundSql) {
        if (DataScopeContext.restrictedUserId() == null) {
            return;
        }
        PluginUtils.MPBoundSql mpBs = PluginUtils.mpBoundSql(boundSql);
        mpBs.sql(parserSingle(mpBs.sql(), null));
    }

    @Override
    public void beforePrepare(StatementHandler sh, Connection connection, Integer transactionTimeout) {
        if (DataScopeContext.restrictedUserId() == null) {
            return;
        }
        PluginUtils.MPStatementHandler mpSh = PluginUtils.mpStatementHandler(sh);
        SqlCommandType sqlCommandType = mpSh.mappedStatement().getSqlCommandType();
        // INSERT 不在此处补归属列，交给 MyMetaObjectHandler
        if (sqlCommandType != SqlCommandType.UPDATE && sqlCommandType != SqlCommandType.DELETE) {
            return;
        }
        PluginUtils.MPBoundSql mpBs = mpSh.mPBoundSql();
        mpBs.sql(parserMulti(mpBs.sql(), null));
    }

    @Override
    protected void processSelect(Select select, int index, String sql, Object obj) {
        final String whereSegment = (String) obj;
        processSelectBody(select, whereSegment);
        List<WithItem> withItemsList = select.getWithItemsList();
        if (!CollectionUtils.isEmpty(withItemsList)) {
            withItemsList.forEach(withItem -> processSelectBody(withItem, whereSegment));
        }
    }

    @Override
    protected void processUpdate(Update update, int index, String sql, Object obj) {
        Table table = update.getTable();
        if (DataScopeTables.ownerColumnOf(table.getName()) == null) {
            return;
        }
        update.setWhere(this.andExpression(table, update.getWhere(), (String) obj));
    }

    @Override
    protected void processDelete(Delete delete, int index, String sql, Object obj) {
        Table table = delete.getTable();
        if (DataScopeTables.ownerColumnOf(table.getName()) == null) {
            return;
        }
        delete.setWhere(this.andExpression(table, delete.getWhere(), (String) obj));
    }

    /**
     * 构建归属条件；表不在白名单、或当前不需要限制时返回 {@code null}（父类据此跳过该表）。
     *
     * <p>每次 new 一个新的 {@link Column}：JSqlParser 的节点带父引用，同一实例挂到表达式树两处
     * 会让后续改写读到错乱的归属关系。</p>
     */
    @Override
    public Expression buildTableExpression(final Table table, final Expression where, final String whereSegment) {
        String ownerColumn = DataScopeTables.ownerColumnOf(table.getName());
        if (ownerColumn == null) {
            return null;
        }
        Long userId = DataScopeContext.restrictedUserId();
        if (userId == null) {
            return null;
        }
        EqualsTo mine = new EqualsTo(aliasColumn(table, ownerColumn), new LongValue(userId));
        IsNullExpression shared = new IsNullExpression();
        shared.setLeftExpression(aliasColumn(table, ownerColumn));
        // 必须整体加括号：外层是 AND 拼接，不加括号会被解析成 "... AND a = 1 OR a IS NULL"，条件当场失效
        return new Parenthesis(new OrExpression(mine, shared));
    }

    /** 归属列的别名限定：有表别名时拼成 {@code 别名.列名}，避免多表查询下的列名歧义。 */
    private Column aliasColumn(Table table, String ownerColumn) {
        StringBuilder column = new StringBuilder();
        if (table.getAlias() != null) {
            column.append(table.getAlias().getName()).append(StringPool.DOT);
        }
        column.append(ownerColumn);
        return new Column(column.toString());
    }
}
