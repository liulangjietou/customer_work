package com.richard.fyoung.customeradmin.aiconfig.experiment.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModelExperimentControllerTest {

    @Test
    void api_shouldExposeStableExperimentRoutes() {
        RequestMapping root = ModelExperimentController.class.getAnnotation(RequestMapping.class);
        assertNotNull(root);
        assertArrayEquals(new String[]{"/api/aiconfig/model-experiments"}, root.value());

        assertArrayEquals(new String[0], method("list").getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/{id}"}, method("get").getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[0], method("create").getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/{id}/start"}, method("start").getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/{id}/stop"}, method("stop").getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/{id}/events"}, method("events").getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/{id}/metrics"}, method("metrics").getAnnotation(GetMapping.class).value());
    }

    @Test
    void api_shouldSeparateReadCreateStartAndStopPermissions() {
        assertPermission("list", "model-experiment:view");
        assertPermission("get", "model-experiment:view");
        assertPermission("events", "model-experiment:view");
        assertPermission("metrics", "model-experiment:view");
        assertPermission("create", "model-experiment:create");
        assertPermission("start", "model-experiment:start");
        assertPermission("stop", "model-experiment:stop");
    }

    @Test
    void mutations_shouldBeOperationAudited_whileReadsStayReadOnly() {
        assertNotNull(method("create").getAnnotation(OperationLog.class));
        assertNotNull(method("start").getAnnotation(OperationLog.class));
        assertNotNull(method("stop").getAnnotation(OperationLog.class));
        assertNull(method("metrics").getAnnotation(OperationLog.class));
        assertNull(method("events").getAnnotation(OperationLog.class));
    }

    private void assertPermission(String methodName, String permission) {
        SaCheckPermission annotation = method(methodName).getAnnotation(SaCheckPermission.class);
        assertNotNull(annotation, methodName);
        assertArrayEquals(new String[]{permission}, annotation.value(), methodName);
    }

    private Method method(String methodName) {
        Method[] methods = Arrays.stream(ModelExperimentController.class.getDeclaredMethods())
            .filter(method -> method.getName().equals(methodName)).toArray(Method[]::new);
        assertEquals(1, methods.length, methodName);
        return methods[0];
    }
}
