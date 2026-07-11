package com.richard.fyoung.customeradmin.common.result;

import lombok.Data;

/**
 * 统一响应体：{code, message, data, timestamp}。
 *
 * <p>{@code code=0} 表示成功；非 0 为业务错误码（见 {@link ResultCode}），
 * {@code message} 面向前端展示，{@code data} 成功时携带业务数据、失败时通常为 {@code null}。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class Result<T> {

    private int code;
    private String message;
    private T data;
    private long timestamp;

    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        r.code = ResultCode.SUCCESS.getCode();
        r.message = ResultCode.SUCCESS.getMessage();
        r.data = data;
        r.timestamp = System.currentTimeMillis();
        return r;
    }

    public static Result<Void> success() {
        return success(null);
    }

    public static <T> Result<T> failure(ResultCode resultCode) {
        return failure(resultCode, resultCode.getMessage());
    }

    public static <T> Result<T> failure(ResultCode resultCode, String message) {
        Result<T> r = new Result<>();
        r.code = resultCode.getCode();
        r.message = message;
        r.timestamp = System.currentTimeMillis();
        return r;
    }
}
