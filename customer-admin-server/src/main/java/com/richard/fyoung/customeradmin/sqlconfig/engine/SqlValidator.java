package com.richard.fyoung.customeradmin.sqlconfig.engine;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;

/**
 * 只读 SQL 校验的 admin 侧薄壳：算法实现在 starter 的
 * {@link com.richard.fyoung.customerwork.sqlkit.SqlValidator}（两侧唯一实现，避免规则双份维护）。
 *
 * <p>本类只负责错误语义转译——starter 抛技术异常 {@link IllegalArgumentException}，这里在全链路
 * 唯一入口处转成携带 {@link ResultCode#SQL_NOT_READONLY} 的 {@link BizException}，
 * 由 {@code GlobalExceptionHandler} 统一出错误响应体。转译只做这一处，调用方不再重复捕获。</p>
 * @author owlzhangfq@gmail.com
 */
public final class SqlValidator {

    private SqlValidator() {
    }

    /** 校验为只读单语句查询，非法直接抛 {@link BizException}（错误码 SQL_NOT_READONLY）。 */
    public static void validateReadOnly(String sql) {
        try {
            com.richard.fyoung.customerwork.sqlkit.SqlValidator.validateReadOnly(sql);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.SQL_NOT_READONLY, e.getMessage());
        }
    }
}
