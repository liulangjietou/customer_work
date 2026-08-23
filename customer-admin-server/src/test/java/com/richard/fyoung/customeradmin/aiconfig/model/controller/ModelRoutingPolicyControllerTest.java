package com.richard.fyoung.customeradmin.aiconfig.model.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ModelRoutingPolicyControllerTest {

    @Test
    void routeReadsAndDryRun_shouldBeReadOnlyWhileVersionWritesRequireEdit() {
        assertPermission("list", "model:view");
        assertPermission("get", "model:view");
        assertPermission("versions", "model:view");
        assertPermission("dryRun", "model:view");
        assertPermission("create", "model:edit");
        assertPermission("validate", "model:edit");
        assertPermission("createVersion", "model:edit");
        assertPermission("activate", "model:edit");
    }

    private void assertPermission(String methodName, String permission) {
        Method[] methods = Arrays.stream(ModelRoutingPolicyController.class.getDeclaredMethods())
            .filter(method -> method.getName().equals(methodName)).toArray(Method[]::new);
        assertEquals(1, methods.length, methodName);
        SaCheckPermission annotation = methods[0].getAnnotation(SaCheckPermission.class);
        assertNotNull(annotation, methodName);
        assertArrayEquals(new String[]{permission}, annotation.value(), methodName);
    }
}
