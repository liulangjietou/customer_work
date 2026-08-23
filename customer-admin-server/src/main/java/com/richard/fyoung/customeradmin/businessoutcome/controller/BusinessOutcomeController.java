package com.richard.fyoung.customeradmin.businessoutcome.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.businessoutcome.dto.BusinessOutcomeSessionPageVO;
import com.richard.fyoung.customeradmin.businessoutcome.dto.BusinessOutcomeSummaryVO;
import com.richard.fyoung.customeradmin.businessoutcome.service.BusinessOutcomeService;
import com.richard.fyoung.customeradmin.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 当前租户的业务结果—成本只读 API。 */
@RestController
@RequestMapping("/api/business-outcomes")
public class BusinessOutcomeController {

    private final BusinessOutcomeService service;

    public BusinessOutcomeController(BusinessOutcomeService service) {
        this.service = service;
    }

    @SaCheckPermission("business-outcome:view")
    @GetMapping("/summary")
    public Result<BusinessOutcomeSummaryVO> summary(@RequestParam long fromMs,
                                                    @RequestParam long toMs,
                                                    @RequestParam(required = false) String agentCode) {
        return Result.success(service.summary(fromMs, toMs, agentCode));
    }

    @SaCheckPermission("business-outcome:view")
    @GetMapping("/sessions")
    public Result<BusinessOutcomeSessionPageVO> sessions(@RequestParam long fromMs,
                                                         @RequestParam long toMs,
                                                         @RequestParam(required = false) String agentCode,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return Result.success(service.sessions(fromMs, toMs, agentCode, page, size));
    }
}
