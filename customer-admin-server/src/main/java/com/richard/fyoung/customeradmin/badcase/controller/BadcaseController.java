package com.richard.fyoung.customeradmin.badcase.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.badcase.config.BadcaseGatewayProvider;
import com.richard.fyoung.customeradmin.badcase.dto.AdoptEvalCaseRequest;
import com.richard.fyoung.customeradmin.badcase.dto.AdoptKnowledgeRequest;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.capability.badcase.Badcase;
import com.richard.fyoung.customerwork.capability.badcase.BadcaseQuery;
import com.richard.fyoung.customerwork.capability.badcase.BadcaseSource;
import com.richard.fyoung.customerwork.capability.badcase.BadcaseStatus;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * badcase 回流：待筛队列、转知识库、转评测用例、忽略。
 *
 * <p>数据与回流目标（知识库 FAQ、评测用例）都在客服端库，本控制器经跨库门面直接操作——
 * 这几件事都不需要真实的模型链，不必像评测触发那样绕 HTTP 到客服端去跑。</p>
 *
 * <p>业务规则（重复采纳拒绝、已处理不可忽略、编号冲突提前拦）全在 starter 的
 * {@code BadcaseService} 里，本类只做参数转换与权限校验。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/badcase")
public class BadcaseController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int FIRST_PAGE = 1;

    private final BadcaseGatewayProvider gatewayProvider;

    public BadcaseController(BadcaseGatewayProvider gatewayProvider) {
        this.gatewayProvider = gatewayProvider;
    }

    /** 待筛队列（默认 PENDING）；status/source 传空即不限。 */
    @SaCheckPermission("badcase:view")
    @GetMapping("/page")
    public Result<PageResult<Badcase>> page(@RequestParam(required = false) BadcaseStatus status,
                                            @RequestParam(required = false) BadcaseSource source,
                                            @RequestParam(defaultValue = "" + FIRST_PAGE) int pageNum,
                                            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int pageSize) {
        int safePageNum = Math.max(pageNum, FIRST_PAGE);
        int safePageSize = Math.max(pageSize, 1);
        int offset = (safePageNum - 1) * safePageSize;

        List<Badcase> rows = gatewayProvider.get()
            .query(new BadcaseQuery(status, source, offset, safePageSize));
        long total = gatewayProvider.get().count(status, source);

        PageResult<Badcase> result = new PageResult<>();
        result.setPageNum(safePageNum);
        result.setPageSize(safePageSize);
        result.setTotal(total);
        result.setList(rows);
        return Result.success(result);
    }

    /** 单条详情（含回查到的一问一答）。 */
    @SaCheckPermission("badcase:view")
    @GetMapping("/{id}")
    public Result<Badcase> detail(@PathVariable String id) {
        return Result.success(gatewayProvider.get().find(id)
            .orElseThrow(() -> new BizException(ResultCode.RESOURCE_NOT_FOUND, "badcase 不存在：" + id)));
    }

    /** 采纳为知识库条目：补上答错的那块知识（治本）。 */
    @SaCheckPermission("badcase:adopt")
    @OperationLog(operation = "badcase转知识库", target = "cw_knowledge")
    @PostMapping("/{id}/adopt-knowledge")
    public Result<Badcase> adoptKnowledge(@PathVariable String id,
                                          @Valid @RequestBody AdoptKnowledgeRequest request) {
        return Result.success(gatewayProvider.get().adoptAsKnowledge(id, request.getTitle(),
            request.getContent(), request.getKeyword(), currentOperator()));
    }

    /** 采纳为评测用例：把这次翻车固化成回归防护（防复发）。与转知识库互不排斥。 */
    @SaCheckPermission("badcase:adopt")
    @OperationLog(operation = "badcase转评测用例", target = "cw_eval_case")
    @PostMapping("/{id}/adopt-eval-case")
    public Result<Badcase> adoptEvalCase(@PathVariable String id,
                                         @Valid @RequestBody AdoptEvalCaseRequest request) {
        return Result.success(gatewayProvider.get().adoptAsEvalCase(id, request.getCaseId(),
            request.getEvalType(), request.getExpected(), request.getCategory(), currentOperator()));
    }

    /** 忽略：噪声反馈或质检误报；保留记录以免被反复翻出来。 */
    @SaCheckPermission("badcase:adopt")
    @OperationLog(operation = "忽略badcase", target = "cw_badcase")
    @PostMapping("/{id}/ignore")
    public Result<Badcase> ignore(@PathVariable String id,
                                  @RequestParam(required = false) String reason) {
        return Result.success(gatewayProvider.get().ignore(id, reason, currentOperator()));
    }

    /** 处理人取当前登录账号，落在 badcase 上供日后复盘"这条是谁判的"。 */
    private String currentOperator() {
        return StpUtil.getLoginIdAsString();
    }
}
