package com.richard.fyoung.customeradmin.ticket.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.ticket.config.CurrentAgentResolver;
import com.richard.fyoung.customeradmin.ticket.dto.TicketCategoryRequest;
import com.richard.fyoung.customeradmin.ticket.dto.TicketCloseRequest;
import com.richard.fyoung.customeradmin.ticket.dto.TicketDetailVO;
import com.richard.fyoung.customeradmin.ticket.dto.TicketHoldRequest;
import com.richard.fyoung.customeradmin.ticket.dto.TicketMessageVO;
import com.richard.fyoung.customeradmin.ticket.dto.TicketPageQuery;
import com.richard.fyoung.customeradmin.ticket.dto.TicketPageResult;
import com.richard.fyoung.customeradmin.ticket.dto.TicketPriorityRequest;
import com.richard.fyoung.customeradmin.ticket.dto.TicketReplyRequest;
import com.richard.fyoung.customeradmin.ticket.dto.TicketResolveRequest;
import com.richard.fyoung.customeradmin.ticket.dto.TicketResumeRequest;
import com.richard.fyoung.customeradmin.ticket.dto.TicketTransferRequest;
import com.richard.fyoung.customeradmin.ticket.dto.WsCredentialVO;
import com.richard.fyoung.customeradmin.ticket.service.UserTicketService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户工单（人工客服后台）：坐席列表/详情/消息查看 + 抢单/回复/挂起/转派/解决/关闭 + 优先级/分类调整，
 * 全部通过 {@link UserTicketService} 代理到 8080；另签发坐席 WS 接入凭证给前端直连 8080。
 *
 * <p>Controller 只做参数编排与权限/操作日志埋点，不直接碰 client；当前登录坐席身份从 Sa-Token 解析
 * （{@link CurrentAgentResolver}），代理调用时由 RestClient 拦截器自动带上 X-Agent-Token。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/ticket")
public class UserTicketController {

    private static final String TARGET = "user_ticket";

    private final UserTicketService ticketService;
    private final CurrentAgentResolver currentAgentResolver;

    public UserTicketController(UserTicketService ticketService, CurrentAgentResolver currentAgentResolver) {
        this.ticketService = ticketService;
        this.currentAgentResolver = currentAgentResolver;
    }

    @SaCheckPermission("user-ticket:view")
    @GetMapping("/page")
    public Result<TicketPageResult> page(TicketPageQuery query) {
        return Result.success(ticketService.page(query));
    }

    @SaCheckPermission("user-ticket:view")
    @GetMapping("/{id}")
    public Result<TicketDetailVO> detail(@PathVariable String id) {
        return Result.success(ticketService.detail(id));
    }

    @SaCheckPermission("user-ticket:view")
    @GetMapping("/{id}/messages")
    public Result<List<TicketMessageVO>> messages(@PathVariable String id,
                                                  @RequestParam(required = false) Long beforeId,
                                                  @RequestParam(required = false) Integer limit) {
        return Result.success(ticketService.messages(id, beforeId, limit));
    }

    @SaCheckPermission("user-ticket:claim")
    @OperationLog(operation = "抢单受理工单", target = TARGET)
    @PostMapping("/{id}/claim")
    public Result<Void> claim(@PathVariable String id) {
        ticketService.claim(id);
        return Result.success();
    }

    @SaCheckPermission("user-ticket:reply")
    @OperationLog(operation = "回复工单", target = TARGET)
    @PostMapping("/{id}/reply")
    public Result<Void> reply(@PathVariable String id, @Valid @RequestBody TicketReplyRequest request) {
        ticketService.reply(id, request.content());
        return Result.success();
    }

    @SaCheckPermission("user-ticket:edit")
    @OperationLog(operation = "挂起工单", target = TARGET)
    @PostMapping("/{id}/hold")
    public Result<Void> hold(@PathVariable String id, @RequestBody(required = false) TicketHoldRequest request) {
        ticketService.hold(id, request == null ? null : request.reason());
        return Result.success();
    }

    @SaCheckPermission("user-ticket:edit")
    @OperationLog(operation = "恢复工单", target = TARGET)
    @PostMapping("/{id}/resume")
    public Result<Void> resume(@PathVariable String id, @RequestBody(required = false) TicketResumeRequest request) {
        ticketService.resume(id);
        return Result.success();
    }

    @SaCheckPermission("user-ticket:transfer")
    @OperationLog(operation = "转派工单", target = TARGET)
    @PostMapping("/{id}/transfer")
    public Result<Void> transfer(@PathVariable String id, @RequestBody(required = false) TicketTransferRequest request) {
        ticketService.transfer(id, request == null ? null : request.toAgent());
        return Result.success();
    }

    @SaCheckPermission("user-ticket:resolve")
    @OperationLog(operation = "解决工单", target = TARGET)
    @PostMapping("/{id}/resolve")
    public Result<Void> resolve(@PathVariable String id, @Valid @RequestBody TicketResolveRequest request) {
        ticketService.resolve(id, request.note());
        return Result.success();
    }

    @SaCheckPermission("user-ticket:close")
    @OperationLog(operation = "关闭工单", target = TARGET)
    @PostMapping("/{id}/close")
    public Result<Void> close(@PathVariable String id, @Valid @RequestBody TicketCloseRequest request) {
        ticketService.close(id, request.reason());
        return Result.success();
    }

    @SaCheckPermission("user-ticket:edit")
    @OperationLog(operation = "调整工单优先级", target = TARGET)
    @PutMapping("/{id}/priority")
    public Result<Void> priority(@PathVariable String id, @Valid @RequestBody TicketPriorityRequest request) {
        ticketService.updatePriority(id, request.priority());
        return Result.success();
    }

    @SaCheckPermission("user-ticket:edit")
    @OperationLog(operation = "调整工单分类", target = TARGET)
    @PutMapping("/{id}/category")
    public Result<Void> category(@PathVariable String id, @Valid @RequestBody TicketCategoryRequest request) {
        ticketService.updateCategory(id, request.category());
        return Result.success();
    }

    /** 签发坐席 WS 接入凭证：客服浏览器凭此直连 8080 的 /ws/agent。 */
    @SaCheckPermission("user-ticket:view")
    @GetMapping("/ws-credential")
    public Result<WsCredentialVO> wsCredential() {
        String agentId = currentAgentResolver.currentAgentId();
        return Result.success(ticketService.issueWsCredential(agentId));
    }
}
