package com.richard.fyoung.customeradmin.ops.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.ops.service.OpsAdminService;
import com.richard.fyoung.customerwork.capability.deadletter.DeadLetter;
import com.richard.fyoung.customerwork.capability.deadletter.DeadLetterStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 死信队列运营入口：待重投 / 已放弃列表与人工重开。
 *
 * <p>重投本身由客服端的巡检器执行，后台只负责"看"和"把已放弃的放回队列"——
 * 重投要调的是客服端的下游依赖，后台没有那套上下文。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/ops/dead-letter")
public class DeadLetterController {

    private static final int DEFAULT_LIMIT = 50;

    private final OpsAdminService opsAdminService;

    public DeadLetterController(OpsAdminService opsAdminService) {
        this.opsAdminService = opsAdminService;
    }

    /** 按状态列出。ABANDONED 那批是真正要人管的——重试次数已耗尽，不补就永远丢了。 */
    @SaCheckPermission("dead-letter:view")
    @GetMapping("/list")
    public Result<List<DeadLetter>> list(@RequestParam DeadLetterStatus status,
                                         @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return Result.success(opsAdminService.listDeadLetters(status, limit));
    }

    /** 各状态计数（角标）。 */
    @SaCheckPermission("dead-letter:view")
    @GetMapping("/stats")
    public Result<Map<String, Long>> stats() {
        return Result.success(Map.of(
            DeadLetterStatus.PENDING.name(), opsAdminService.countDeadLetters(DeadLetterStatus.PENDING),
            DeadLetterStatus.SUCCEEDED.name(), opsAdminService.countDeadLetters(DeadLetterStatus.SUCCEEDED),
            DeadLetterStatus.ABANDONED.name(), opsAdminService.countDeadLetters(DeadLetterStatus.ABANDONED)));
    }

    /**
     * 人工重开：确认下游恢复后把已放弃的放回待重投队列。
     *
     * <p>重开会清零重试次数——不清零的话刚放回去就又立刻耗尽，等于没重开。</p>
     */
    @SaCheckPermission("dead-letter:reopen")
    @OperationLog(operation = "重开死信", target = "cw_dead_letter")
    @PostMapping("/{id}/reopen")
    public Result<DeadLetter> reopen(@PathVariable String id) {
        return Result.success(opsAdminService.reopenDeadLetter(id));
    }
}
