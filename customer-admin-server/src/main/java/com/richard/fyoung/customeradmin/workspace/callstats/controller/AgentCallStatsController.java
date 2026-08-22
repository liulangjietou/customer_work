package com.richard.fyoung.customeradmin.workspace.callstats.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsDetailVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsPageVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsQuery;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallReplayManifestVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsSummaryVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallTrendVO;
import com.richard.fyoung.customeradmin.workspace.callstats.service.AgentCallStatsService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 智能体调用分段耗时统计查询（只读为主 + 删除）。数据由 {@code AgentCallTimingMiddleware} 采集落库，
 * {@code source=ADMIN}（默认，本库）/{@code APP}（客服端库 agent_scope_customer_work）双源可查。
 * 鉴权：查询点 {@code agent-call-stats:view}、删除点 {@code agent-call-stats:delete}（与 V38 种子一致）。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/agent-call-stats")
public class AgentCallStatsController {

    private static final String DEFAULT_SOURCE = "ADMIN";

    private final AgentCallStatsService callStatsService;

    public AgentCallStatsController(AgentCallStatsService callStatsService) {
        this.callStatsService = callStatsService;
    }

    /** 分页查询（{total, rows}），支持 source/username/agentCode/sessionType/requestId/sessionId/时间范围过滤。 */
    @SaCheckPermission("agent-call-stats:view")
    @GetMapping("/page")
    public Result<AgentCallStatsPageVO> page(AgentCallStatsQuery query) {
        return Result.success(callStatsService.page(query));
    }

    /** 调用汇总（总数/平均总耗时/最大总耗时/各段平均），筛选条件同 page。 */
    @SaCheckPermission("agent-call-stats:view")
    @GetMapping("/summary")
    public Result<AgentCallStatsSummaryVO> summary(AgentCallStatsQuery query) {
        return Result.success(callStatsService.summary(query));
    }

    /** 调用趋势（granularity=day|hour，含各段平均耗时），筛选条件同 page。 */
    @SaCheckPermission("agent-call-stats:view")
    @GetMapping("/trend")
    public Result<List<AgentCallTrendVO>> trend(AgentCallStatsQuery query) {
        return Result.success(callStatsService.trend(query));
    }

    /** 调用明细（全量字段 + 回答全文 + 分段列表）。 */
    @SaCheckPermission("agent-call-stats:view")
    @GetMapping("/{id}")
    public Result<AgentCallStatsDetailVO> detail(@PathVariable long id,
                                                 @RequestParam(defaultValue = DEFAULT_SOURCE) String source) {
        return Result.success(callStatsService.detail(id, source));
    }

    /** 只读重放清单：不再次执行模型或工具，避免查看权限产生外部副作用。 */
    @SaCheckPermission("agent-call-stats:view")
    @GetMapping("/{id}/replay-manifest")
    public Result<AgentCallReplayManifestVO> replayManifest(
        @PathVariable long id,
        @RequestParam(defaultValue = DEFAULT_SOURCE) String source) {
        return Result.success(callStatsService.replayManifest(id, source));
    }

    /** 删除一条调用（主记录 + 分段级联）。 */
    @SaCheckPermission("agent-call-stats:delete")
    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable long id,
                                  @RequestParam(defaultValue = DEFAULT_SOURCE) String source) {
        return Result.success(callStatsService.delete(id, source));
    }
}
