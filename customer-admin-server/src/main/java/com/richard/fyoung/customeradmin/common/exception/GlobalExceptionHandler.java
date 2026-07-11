package com.richard.fyoung.customeradmin.common.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理：把各类异常统一转换为 {@link Result} 标准响应体，不向前端暴露堆栈。
 * @author owlzhangfq@gmail.com
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotLoginException.class)
    public Result<Void> handleNotLogin(NotLoginException e) {
        return Result.failure(ResultCode.UNAUTHORIZED);
    }

    @ExceptionHandler(NotPermissionException.class)
    public Result<Void> handleNotPermission(NotPermissionException e) {
        return Result.failure(ResultCode.FORBIDDEN, "缺少权限点：" + e.getPermission());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
            .map(err -> err.getField() + ": " + err.getDefaultMessage())
            .collect(Collectors.joining("; "));
        return Result.failure(ResultCode.PARAM_INVALID, detail.isBlank() ? ResultCode.PARAM_INVALID.getMessage() : detail);
    }

    @ExceptionHandler(BindException.class)
    public Result<Void> handleBind(BindException e) {
        String detail = e.getFieldErrors().stream()
            .map(err -> ((FieldError) err).getField() + ": " + err.getDefaultMessage())
            .collect(Collectors.joining("; "));
        return Result.failure(ResultCode.PARAM_INVALID, detail.isBlank() ? ResultCode.PARAM_INVALID.getMessage() : detail);
    }

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        return Result.failure(e.getResultCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleGeneric(Exception e) {
        log.error("unhandled exception, code={}", "ADMIN-UNHANDLED-ERROR", e);
        return Result.failure(ResultCode.SYSTEM_ERROR);
    }
}
