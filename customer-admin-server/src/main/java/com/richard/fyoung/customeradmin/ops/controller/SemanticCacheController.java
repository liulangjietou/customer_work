package com.richard.fyoung.customeradmin.ops.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.ops.service.OpsAdminService;
import com.richard.fyoung.customerwork.capability.semanticcache.SemanticCacheEntry;
import com.richard.fyoung.customerwork.capability.semanticcache.SemanticCacheScope;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 语义缓存运营入口：看缓存了什么、哪些真在被复用、清掉答得不对的。
 *
 * <p>缓存本身跑在客服端，这里经跨库门面直接读写——清缓存不需要真实模型链，
 * 不必像评测触发那样绕 HTTP。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/ops/semantic-cache")
public class SemanticCacheController {

    private static final int DEFAULT_LIMIT = 50;
    private static final String DEFAULT_SCOPE = "default";

    /** 分区选择器一次最多列多少个：分区数随活跃用户数增长，不能全量拉。 */
    private static final int DEFAULT_SCOPE_LIMIT = 100;

    private final OpsAdminService opsAdminService;

    public SemanticCacheController(OpsAdminService opsAdminService) {
        this.opsAdminService = opsAdminService;
    }

    /**
     * 实际存在的缓存分区（按条目数降序），供看板做分区选择。
     *
     * <p>分区键是用户级隔离键（两个用户问同一句话答案未必相同，按用户隔离是安全底线），
     * 运营既猜不到有哪些分区，也不知道哪个里面有东西——列出来让他选，而不是让他手填。</p>
     */
    @SaCheckPermission("semantic-cache:view")
    @GetMapping("/scopes")
    public Result<List<SemanticCacheScope>> scopes(
            @RequestParam(defaultValue = "" + DEFAULT_SCOPE_LIMIT) int limit) {
        return Result.success(opsAdminService.listCacheScopes(limit));
    }

    /** 缓存条目，按命中次数降序——一眼看出哪些真在被复用、哪些只是白占容量。 */
    @SaCheckPermission("semantic-cache:view")
    @GetMapping("/list")
    public Result<List<SemanticCacheEntry>> list(
            @RequestParam(defaultValue = DEFAULT_SCOPE) String scopeId,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return Result.success(opsAdminService.listCache(scopeId, limit));
    }

    /** 定点删除单条：发现某条答得不对时不必清空整个分区。 */
    @SaCheckPermission("semantic-cache:evict")
    @OperationLog(operation = "删除语义缓存条目", target = "cw_semantic_cache")
    @DeleteMapping("/{id}")
    public Result<Boolean> evict(@PathVariable Long id) {
        return Result.success(opsAdminService.evictCacheEntry(id));
    }

    /** 清空分区：知识库或提示词改过之后，旧答案不再可信，应整体作废。 */
    @SaCheckPermission("semantic-cache:evict")
    @OperationLog(operation = "清空语义缓存", target = "cw_semantic_cache")
    @DeleteMapping("/scope/{scopeId}")
    public Result<Integer> clear(@PathVariable String scopeId) {
        return Result.success(opsAdminService.clearCache(scopeId));
    }
}
