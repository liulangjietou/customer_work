package com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.dto.EvalGateOverrideRequest;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.dto.EvalGatePolicyRequest;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class EvalReleaseGateControllerTest {

    @Test
    void policyOverrideAndRetryShouldUseDistinctLeastPrivilegePermissions() throws Exception {
        assertPermission("savePolicy", new Class<?>[]{EvalType.class, EvalGatePolicyRequest.class},
            "eval:gate-policy-edit");
        assertPermission("override", new Class<?>[]{String.class, EvalGateOverrideRequest.class},
            "eval:gate-override");
        assertPermission("retry", new Class<?>[]{String.class}, "eval:run");
        assertPermission("task", new Class<?>[]{String.class}, "eval:view");
    }

    private void assertPermission(String methodName, Class<?>[] parameterTypes, String permission)
        throws Exception {
        Method method = EvalReleaseGateController.class.getMethod(methodName, parameterTypes);
        SaCheckPermission annotation = method.getAnnotation(SaCheckPermission.class);
        assertArrayEquals(new String[]{permission}, annotation.value());
    }
}
