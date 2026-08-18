package com.richard.fyoung.customeradmin.workspace.vibecoding.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.ReviewRequest;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.ReviewTaskVO;
import com.richard.fyoung.customeradmin.workspace.vibecoding.entity.CodeReviewTask;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.CollaborativeCodingService;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.GitAssistantService;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.VibeCodingService;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.AiCodingTaskService;
import com.richard.fyoung.customeradmin.workspace.runtime.SandboxCommandService;
import com.richard.fyoung.customeradmin.workspace.runtime.SandboxCommandEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.workspace.session.service.WorkspaceSessionGuard;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.CommandExecuteRequest;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.CommandOutputEvent;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VibeCodingController} 代码审查异步接口单测：提交返回 taskId、轮询透传归属校验参数。
 * @author owlzhangfq@gmail.com
 */
class VibeCodingControllerReviewTest {

    private static final long CURRENT_USER = 7L;

    private final GitAssistantService gitAssistantService = mock(GitAssistantService.class);
    private final WorkspaceSessionGuard sessionGuard = mock(WorkspaceSessionGuard.class);
    private final SandboxCommandService sandboxCommandService = mock(SandboxCommandService.class);
    private final VibeCodingController controller = new VibeCodingController(
        mock(VibeCodingService.class), gitAssistantService, mock(CollaborativeCodingService.class), sessionGuard,
        sandboxCommandService, mock(AiCodingTaskService.class), new ObjectMapper());

    @Test
    void review_shouldSubmitWithCurrentUser_andReturnTaskId() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(CURRENT_USER);
            when(gitAssistantService.submitReview("coder", "s1", CURRENT_USER)).thenReturn(88L);

            Result<Long> result = controller.review("coder", new ReviewRequest("s1"));

            assertEquals(88L, result.getData());
            verify(gitAssistantService).submitReview(eq("coder"), eq("s1"), eq(CURRENT_USER));
        }
    }

    @Test
    void reviewTask_shouldQueryWithCurrentUser() {
        ReviewTaskVO vo = new ReviewTaskVO(88L, CodeReviewTask.STATUS_RUNNING, null, null, null, null);
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(CURRENT_USER);
            when(gitAssistantService.getReviewTask(88L, CURRENT_USER)).thenReturn(vo);

            Result<ReviewTaskVO> result = controller.reviewTask("coder", 88L);

            assertEquals(vo, result.getData());
            verify(gitAssistantService).getReviewTask(eq(88L), eq(CURRENT_USER));
        }
    }

    @Test
    void execute_shouldClaimSessionAndMapCommandEventsToSse() {
        when(sandboxCommandService.execute("coder", "s1", CURRENT_USER, "mvn test"))
            .thenReturn(Flux.just(new SandboxCommandEvent("command_output",
                new CommandOutputEvent("combined", "running", 1L))));
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(CURRENT_USER);

            List<org.springframework.http.codec.ServerSentEvent<String>> events = controller
                .execute("coder", new CommandExecuteRequest("s1", "mvn test"))
                .collectList().block();

            assertEquals(List.of("command_output", "done"), events.stream().map(e -> e.event()).toList());
            verify(sessionGuard).claimOrRequire("coder", "s1", CURRENT_USER);
        }
    }
}
