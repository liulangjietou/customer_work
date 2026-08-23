package com.richard.fyoung.customeradmin.aiconfig.model.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelConfigService;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ModelOps 接口权限与健康探测委托契约测试。 */
class ModelConfigControllerTest {

    @Test
    void externalHealthCalls_shouldUseDedicatedCostBearingPermission() throws Exception {
        assertPermission("testConnectivity", "model:health-test");
        assertPermission("healthCheck", "model:health-test");
        assertPermission("health", "model:view");
        assertPermission("healthEvents", "model:view");
        assertPermission("impact", "model:view");
        assertPermission("rotateCredential", "model:edit");
        assertPermission("certification", "model:view");
        assertPermission("certificationHistory", "model:view");
        assertPermission("certify", "model:certify");
    }

    @Test
    void healthCheck_shouldDelegateToGovernedProbePath() throws Exception {
        ModelConfigService service = mock(ModelConfigService.class);
        ModelTestResult expected = new ModelTestResult(
            ConnectivityTestStatus.SUCCESS, LocalDateTime.now(), null);
        when(service.testConnectivity(7L)).thenReturn(CompletableFuture.completedFuture(expected));
        ModelConfigController controller = new ModelConfigController(service);

        ModelTestResult actual = controller.healthCheck(7L).get().getData();

        assertEquals(expected, actual);
        verify(service).testConnectivity(7L);
    }

    private void assertPermission(String methodName, String permission) {
        Method[] methods = java.util.Arrays.stream(ModelConfigController.class.getDeclaredMethods())
            .filter(method -> method.getName().equals(methodName))
            .toArray(Method[]::new);
        assertEquals(1, methods.length, methodName);
        SaCheckPermission annotation = methods[0].getAnnotation(SaCheckPermission.class);
        assertNotNull(annotation, methodName);
        assertArrayEquals(new String[]{permission}, annotation.value(), methodName);
    }
}
