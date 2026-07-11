package com.richard.fyoung.customeradmin.common.log;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.system.log.entity.SysOperationLog;
import com.richard.fyoung.customeradmin.system.log.mapper.OperationLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

/**
 * 操作日志 AOP 切面：环绕标注 {@link OperationLog} 的方法，记录操作人/时间/对象/结果/IP。
 *
 * <p>适用于"已登录用户执行的操作"（增删改/模型测试/权限变更等，均已过 Sa-Token 拦截器要求登录），
 * 当前用户信息取自 {@code StpUtil}（登录时已把 username 写入 TokenSession，见
 * {@code AuthService#login}）。<b>登录/登出本身不用本切面</b>——登录失败时尚无登录态，
 * 由 {@code AuthService} 直接调用 {@link OperationLogMapper} 记录，见其实现。</p>
 * @author owlzhangfq@gmail.com
 */
@Aspect
@Component
public class OperationLogAspect {

    private static final Logger log = LoggerFactory.getLogger(OperationLogAspect.class);

    private final OperationLogMapper operationLogMapper;
    private final SensitiveDataMasker masker;

    public OperationLogAspect(OperationLogMapper operationLogMapper, SensitiveDataMasker masker) {
        this.operationLogMapper = operationLogMapper;
        this.masker = masker;
    }

    @Around("@annotation(operationLog)")
    public Object around(ProceedingJoinPoint joinPoint, OperationLog operationLog) throws Throwable {
        Object[] args = joinPoint.getArgs();
        String target = operationLog.target().isBlank()
            ? joinPoint.getTarget().getClass().getSimpleName() : operationLog.target();
        String method = resolveMethodSignature(joinPoint);
        String params = masker.maskToJson(args.length == 1 ? args[0] : args);
        String ip = resolveClientIp();

        try {
            Object result = joinPoint.proceed();
            recordAsync(operationLog.operation(), method, target, params, 1, null, ip);
            return result;
        } catch (Throwable t) {
            recordAsync(operationLog.operation(), method, target, params, 0, t.getMessage(), ip);
            throw t;
        }
    }

    /** 落库用 {@code @Async} 异步，不拖慢主流程 P95 响应时间。 */
    @Async
    public void recordAsync(String operation, String method, String target, String params,
                            int result, String errorMsg, String ip) {
        try {
            SysOperationLog entity = new SysOperationLog();
            if (StpUtil.isLogin()) {
                entity.setUserId(StpUtil.getLoginIdAsLong());
                entity.setUsername(StpUtil.getTokenSession().getString("username"));
            }
            entity.setOperation(operation);
            entity.setMethod(method);
            entity.setTarget(target);
            entity.setParams(params);
            entity.setResult(result);
            entity.setErrorMsg(errorMsg);
            entity.setIp(ip);
            entity.setCreateTime(LocalDateTime.now());
            operationLogMapper.insert(entity);
        } catch (Exception e) {
            log.error("record operation log failed, code={}", "OPERATION-LOG-RECORD-FAIL", e);
        }
    }

    private String resolveMethodSignature(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }

    private String resolveClientIp() {
        ServletRequestAttributes attrs =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
