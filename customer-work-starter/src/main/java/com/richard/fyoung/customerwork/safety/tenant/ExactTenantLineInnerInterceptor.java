package com.richard.fyoung.customerwork.safety.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.create.table.ColDataType;

/** 租户是身份标识，比较必须精确，不能跟随业务文本列的大小写不敏感排序规则。 */
final class ExactTenantLineInnerInterceptor extends TenantLineInnerInterceptor {

    ExactTenantLineInnerInterceptor(TenantLineHandler handler) {
        super(handler);
    }

    /** 仅转换过滤条件右侧的常量，不包装租户列；表别名与 INSERT 的原始租户文本保持不变。 */
    @Override
    public Expression buildTableExpression(Table table, Expression where, String whereSegment) {
        Expression condition = super.buildTableExpression(table, where, whereSegment);
        if (condition == null) {
            return null;
        }
        // 当前 MyBatis-Plus 的扩展点返回单个租户等值条件；其忽略表和缺上下文行为继续由父类负责。
        EqualsTo equality = (EqualsTo) condition;
        ColDataType binaryType = new ColDataType();
        binaryType.setDataType("BINARY");
        CastExpression exactValue = new CastExpression();
        exactValue.setUseCastKeyword(true);
        exactValue.setColDataType(binaryType);
        exactValue.setLeftExpression(equality.getRightExpression());
        equality.setRightExpression(exactValue);
        return equality;
    }
}
