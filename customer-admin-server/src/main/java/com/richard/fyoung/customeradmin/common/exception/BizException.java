package com.richard.fyoung.customeradmin.common.exception;

import com.richard.fyoung.customeradmin.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常：携带 {@link ResultCode}，由 {@code GlobalExceptionHandler} 统一转换为标准错误响应体。
 * 外部依赖失败（模型测试/MCP 连接等）在 Service 层 catch 后应转换为本异常主动抛出，
 * 不让裸异常穿透到 Controller。
 * @author owlzhangfq@gmail.com
 */
@Getter
public class BizException extends RuntimeException {

    private final ResultCode resultCode;

    public BizException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.resultCode = resultCode;
    }

    public BizException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }
}
