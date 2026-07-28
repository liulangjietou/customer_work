package com.richard.fyoung.customeradmin.contentguard.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.contentguard.dto.SensitiveWordHitLogPageQuery;
import com.richard.fyoung.customeradmin.contentguard.dto.SensitiveWordHitLogVO;
import com.richard.fyoung.customeradmin.contentguard.dto.SensitiveWordHitStatsVO;
import com.richard.fyoung.customeradmin.contentguard.service.SensitiveWordHitLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 敏感词命中看板：命中明细分页 + 统计聚合。只读，无写接口。
 *
 * <p>数据来自客服端库 {@code cw_sensitive_word_hit_log}，仅当客服端开启
 * {@code customer-work.sensitive-word.hit-log.enabled=true} 且 {@code store-mode=jdbc} 时才有数据。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/contentguard/hit-log")
public class SensitiveWordHitLogController {

    private final SensitiveWordHitLogService hitLogService;

    public SensitiveWordHitLogController(SensitiveWordHitLogService hitLogService) {
        this.hitLogService = hitLogService;
    }

    @SaCheckPermission("sensitive-hit-log:view")
    @GetMapping("/page")
    public Result<PageResult<SensitiveWordHitLogVO>> page(SensitiveWordHitLogPageQuery query) {
        return Result.success(hitLogService.page(query));
    }

    /** 看板统计：与明细列表共用同一套筛选条件。 */
    @SaCheckPermission("sensitive-hit-log:view")
    @GetMapping("/stats")
    public Result<SensitiveWordHitStatsVO> stats(SensitiveWordHitLogPageQuery query) {
        return Result.success(hitLogService.stats(query));
    }
}
