package com.richard.fyoung.customeradmin.ops.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeGapReviewRequest;
import com.richard.fyoung.customeradmin.ops.service.KnowledgeGapReviewService;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGapCategory;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGapPriority;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 核对权限声明、服务端操作人和真实 MVC 请求校验，不把前端按钮隐藏当作授权。 */
class KnowledgeGapReviewControllerTest {
    private static final String QUESTION_HASH = "a".repeat(64);

    @Test
    void reviewRequiresBothPermissionsAndUsesTheTrustedOperator() throws Exception {
        var write = KnowledgeGapReviewController.class.getMethod("review", String.class,
            KnowledgeGapReviewRequest.class).getAnnotation(SaCheckPermission.class);
        var read = KnowledgeGapReviewController.class.getMethod("detail", String.class, long.class)
            .getAnnotation(SaCheckPermission.class);
        assertArrayEquals(new String[]{"knowledge-gap:view", "improvement:manage"}, write.value());
        assertEquals(SaMode.AND, write.mode());
        assertArrayEquals(new String[]{"knowledge-gap:view"}, read.value());
        var service = mock(KnowledgeGapReviewService.class);
        var controller = new KnowledgeGapReviewController(service);
        var request = new KnowledgeGapReviewRequest(0, KnowledgeGapCategory.DEPENDENCY,
            KnowledgeGapPriority.HIGH, "已核对依赖异常");
        try (var auth = mockStatic(StpUtil.class)) {
            auth.when(StpUtil::getLoginIdAsString).thenReturn("42");
            controller.review(QUESTION_HASH, request);
            verify(service).review(eq(QUESTION_HASH), eq(request), eq("42"));
        }
    }

    @Test
    void invalidRequestsMustBeRejectedBeforeTheReviewService() throws Exception {
        var service = mock(KnowledgeGapReviewService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeGapReviewController(service)).build();
        String valid = """
            {"expectedRevision":0,"category":"DEPENDENCY","priority":"HIGH","reason":"已核对依赖异常"}
            """;
        for (String body : new String[]{
            valid.replace("已核对依赖异常", "   "),
            valid.replace("已核对依赖异常", "据".repeat(1001)),
            valid.replace(":0", ":-1"),
            valid.replace(":0", ":9007199254740991"),
            valid.replace("\"HIGH\"", "null"),
            valid.replace("DEPENDENCY", "INVALID")}) {
            mvc.perform(post("/api/ops/knowledge-gap/reviews/" + QUESTION_HASH)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
}
