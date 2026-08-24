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
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.concurrent.CompletionStage;

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

        SysOperationLog audit = start(operationLog.operation(), method, target, params, ip);
        try {
            Object result = joinPoint.proceed();
            if (result instanceof CompletionStage<?> stage) {
                return stage.whenComplete((ignored, error) -> complete(audit,
                    error == null ? SysOperationLog.RESULT_SUCCESS : SysOperationLog.RESULT_FAILURE,
                    error == null ? null : error.getMessage()));
            }
            complete(audit, SysOperationLog.RESULT_SUCCESS, null);
            return result;
        } catch (Throwable t) {
            complete(audit, SysOperationLog.RESULT_FAILURE, t.getMessage());
            throw t;
        }
    }

    /** 业务执行前同步写 STARTED；写入失败必须阻止高风险操作继续。 */
    private SysOperationLog start(String operation, String method, String target,
                                  String params, String ip) {
        try {
            SysOperationLog entity = new SysOperationLog();
            captureActor(entity);
            entity.setOperation(operation);
            entity.setMethod(method);
            entity.setTarget(target);
            entity.setParams(params);
            entity.setResult(SysOperationLog.RESULT_PENDING);
            entity.setIp(ip);
            entity.initializeAudit(SysOperationLog.AUDIT_STARTED, LocalDateTime.now());
            if (operationLogMapper.insert(entity) != 1) {
                throw new IllegalStateException("operation audit start was not persisted");
            }
            return entity;
        } catch (Exception e) {
            log.error("record operation audit start failed, code={}",
                "OPERATION-AUDIT-START-FAIL", e);
            throw new IllegalStateException("操作审计留痕失败，已阻止本次操作", e);
        }
    }

    /** 终态补写失败时保留 STARTED 行，供巡检识别不确定操作。 */
    private void complete(SysOperationLog entity, int result, String errorMsg) {
        try {
            if (operationLogMapper.completeAudit(
                entity.getId(), entity.getEventId(), result, errorMsg) != 1) {
                throw new IllegalStateException("operation audit completion was not persisted");
            }
            entity.setResult(result);
            entity.setErrorMsg(errorMsg);
            entity.setAuditStatus(SysOperationLog.AUDIT_COMPLETED);
        } catch (Exception e) {
            log.error("record operation audit completion failed, code={}, eventId={}",
                "OPERATION-AUDIT-COMPLETE-FAIL", entity.getEventId(), e);
        }
    }

    private void captureActor(SysOperationLog entity) {
        try {
            if (!StpUtil.isLogin()) {
                throw new IllegalStateException("authenticated operation audit actor is missing");
            }
            entity.setUserId(StpUtil.getLoginIdAsLong());
            entity.setUsername(StpUtil.getTokenSession().getString("username"));
        } catch (Exception e) {
            log.error("resolve operation audit actor failed, code={}",
                "OPERATION-AUDIT-ACTOR-FAIL", e);
            throw new IllegalStateException("操作审计无法确认操作人，已阻止本次操作", e);
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
