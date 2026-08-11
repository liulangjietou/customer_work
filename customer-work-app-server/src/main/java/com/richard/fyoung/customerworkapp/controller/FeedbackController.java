package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.core.dto.FeedbackRequest;
import com.richard.fyoung.customerwork.capability.feedback.FeedbackService;
import com.richard.fyoung.customerwork.capability.feedback.MessageFeedback;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 用户反馈端点（消息级点赞/点踩，数据飞轮的用户主动输入通道）。
 *
 * <p>与 {@code QualityInspectionService}（系统主动质检）互补：本端点让终端用户对具体一条回复
 * 表达态度，DOWN 类型自动沉淀到 {@code FactLog} 供离线复盘（诚实边界：只记录，不自动回流知识库，
 * 见 {@link FeedbackService}）。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/customer/feedback")
@Tag(name = "用户反馈", description = "消息级点赞/点踩，负反馈沉淀供数据飞轮复盘")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @Operation(summary = "提交消息反馈", description = "同一 messageId 重复提交按最新一次覆盖")
    @PostMapping
    public Mono<MessageFeedback> submit(@Valid @RequestBody FeedbackRequest request) {
        return Mono.fromCallable(() -> feedbackService.submit(
            request.sessionId(), request.messageId(), request.type(), request.comment()));
    }

    @Operation(summary = "查询单条消息反馈")
    @GetMapping("/{messageId}")
    public Mono<MessageFeedback> get(@PathVariable String messageId) {
        return Mono.justOrEmpty(feedbackService.find(messageId))
            .switchIfEmpty(Mono.error(new ResponseStatusException(
                HttpStatus.NOT_FOUND, "feedback not found: " + messageId)));
    }

    @Operation(summary = "查询某会话全部反馈")
    @GetMapping
    public Mono<List<MessageFeedback>> listBySession(@RequestParam String sessionId) {
        return Mono.fromCallable(() -> feedbackService.findBySession(sessionId));
    }
}
