package com.richard.fyoung.customeradmin.common.log;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.system.log.entity.SysOperationLog;
import com.richard.fyoung.customeradmin.system.log.mapper.OperationLogMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationLogAspectTest {

    @Test
    void persistsStartedBeforeBusinessAndCompletesSameEvent() throws Throwable {
        OperationLogMapper mapper = mock(OperationLogMapper.class);
        SensitiveDataMasker masker = mock(SensitiveDataMasker.class);
        ProceedingJoinPoint joinPoint = joinPoint();
        OperationLog annotation = annotation();
        AtomicReference<Integer> insertedResult = new AtomicReference<>();
        AtomicReference<String> insertedAuditStatus = new AtomicReference<>();
        when(masker.maskToJson(any())).thenReturn("{}");
        when(mapper.insert(any(SysOperationLog.class))).thenAnswer(invocation -> {
            SysOperationLog entity = invocation.getArgument(0);
            insertedResult.set(entity.getResult());
            insertedAuditStatus.set(entity.getAuditStatus());
            entity.setId(90L);
            return 1;
        });
        when(mapper.completeAudit(any(), any(), anyInt(), nullable(String.class))).thenReturn(1);
        when(joinPoint.proceed()).thenReturn("ok");
        OperationLogAspect aspect = new OperationLogAspect(mapper, masker);

        Object result;
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stubActor(stp);
            result = aspect.around(joinPoint, annotation);
        }

        assertEquals("ok", result);
        InOrder order = inOrder(mapper, joinPoint);
        order.verify(mapper).insert(any(SysOperationLog.class));
        order.verify(joinPoint).proceed();
        order.verify(mapper).completeAudit(any(), any(), anyInt(), nullable(String.class));
        ArgumentCaptor<SysOperationLog> captor = ArgumentCaptor.forClass(SysOperationLog.class);
        verify(mapper).insert(captor.capture());
        assertEquals(SysOperationLog.RESULT_PENDING, insertedResult.get());
        assertEquals(SysOperationLog.AUDIT_STARTED, insertedAuditStatus.get());
        assertNotNull(captor.getValue().getEventId());
        assertNotNull(captor.getValue().getRetentionUntil());
    }

    @Test
    void auditStartFailureBlocksBusinessExecution() throws Throwable {
        OperationLogMapper mapper = mock(OperationLogMapper.class);
        SensitiveDataMasker masker = mock(SensitiveDataMasker.class);
        ProceedingJoinPoint joinPoint = joinPoint();
        when(masker.maskToJson(any())).thenReturn("{}");
        when(mapper.insert(any(SysOperationLog.class))).thenReturn(0);
        OperationLogAspect aspect = new OperationLogAspect(mapper, masker);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stubActor(stp);
            assertThrows(IllegalStateException.class, () -> aspect.around(joinPoint, annotation()));
        }

        verify(joinPoint, never()).proceed();
    }

    @Test
    void asynchronousOperationCompletesAuditOnlyWhenFutureTerminates() throws Throwable {
        OperationLogMapper mapper = mock(OperationLogMapper.class);
        SensitiveDataMasker masker = mock(SensitiveDataMasker.class);
        ProceedingJoinPoint joinPoint = joinPoint();
        CompletableFuture<String> future = new CompletableFuture<>();
        when(masker.maskToJson(any())).thenReturn("{}");
        when(mapper.insert(any(SysOperationLog.class))).thenAnswer(invocation -> {
            SysOperationLog entity = invocation.getArgument(0);
            entity.setId(91L);
            return 1;
        });
        when(mapper.completeAudit(any(), any(), anyInt(), nullable(String.class))).thenReturn(1);
        when(joinPoint.proceed()).thenReturn(future);
        OperationLogAspect aspect = new OperationLogAspect(mapper, masker);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stubActor(stp);
            aspect.around(joinPoint, annotation());
        }
        verify(mapper, never()).completeAudit(any(), any(), anyInt(), nullable(String.class));

        future.complete("ok");

        verify(mapper).completeAudit(eq(91L), anyString(),
            eq(SysOperationLog.RESULT_SUCCESS), isNull());
    }

    private ProceedingJoinPoint joinPoint() throws Exception {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = SampleController.class.getDeclaredMethod("change");
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(joinPoint.getTarget()).thenReturn(new SampleController());
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        return joinPoint;
    }

    private OperationLog annotation() {
        OperationLog annotation = mock(OperationLog.class);
        when(annotation.operation()).thenReturn("change");
        when(annotation.target()).thenReturn("resource");
        return annotation;
    }

    private void stubActor(MockedStatic<StpUtil> stp) {
        SaSession session = mock(SaSession.class);
        stp.when(StpUtil::isLogin).thenReturn(true);
        stp.when(StpUtil::getLoginIdAsLong).thenReturn(11L);
        stp.when(StpUtil::getTokenSession).thenReturn(session);
        when(session.getString("username")).thenReturn("operator");
    }

    private static class SampleController {
        void change() {
        }
    }
}
