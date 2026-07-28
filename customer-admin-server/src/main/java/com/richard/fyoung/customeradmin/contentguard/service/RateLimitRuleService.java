package com.richard.fyoung.customeradmin.contentguard.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardGatewayProvider;
import com.richard.fyoung.customeradmin.contentguard.dto.RateLimitRuleSaveRequest;
import com.richard.fyoung.customeradmin.contentguard.dto.RateLimitRuleVO;
import com.richard.fyoung.customeradmin.contentguard.jdbc.ContentGuardGateway;
import com.richard.fyoung.customerwork.security.ratelimit.RateLimitAlgorithm;
import com.richard.fyoung.customerwork.security.ratelimit.RateLimitDimension;
import com.richard.fyoung.customerwork.security.ratelimit.entity.RateLimitRuleEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 限流规则管理：分页查询、增删改、启停。
 *
 * <p><b>分页在内存里做，是刻意的</b>：限流规则天然只有几十条（每条覆盖一整类路径），
 * 为它单独写一套分页/计数 SQL 属于给自己找活干。全量取回后按关键字与启停筛、按优先级排，
 * 再切片——若哪天规则真的涨到几千条，那说明规则设计出了问题，该治理的是规则不是分页。</p>
 *
 * <p>写的是客服端库 {@code cw_rate_limit_rule}，starter 侧 {@code RateLimitRuleProvider} 轮询指纹自动换快照，
 * 默认 60 秒内生效——所以这里每次写入都刷新 {@code updated_at_ms}，它是指纹的组成部分。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class RateLimitRuleService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitRuleService.class);

    private final ContentGuardGatewayProvider gatewayProvider;

    public RateLimitRuleService(ContentGuardGatewayProvider gatewayProvider) {
        this.gatewayProvider = gatewayProvider;
    }

    /** 分页查询规则（按优先级升序，与运行时的匹配顺序一致——运营看到的顺序就是生效顺序）。 */
    public PageResult<RateLimitRuleVO> page(PageQuery query) {
        List<RateLimitRuleEntity> all = gatewayProvider.get().ruleMapper()
            .selectList(new QueryWrapper<RateLimitRuleEntity>().orderByAsc("priority").orderByAsc("id"));
        List<RateLimitRuleVO> filtered = new ArrayList<>();
        for (RateLimitRuleEntity row : all) {
            if (!matches(row, query)) {
                continue;
            }
            filtered.add(toVO(row));
        }
        long pageSize = query.getPageSize() <= 0 ? 10 : query.getPageSize();
        long pageNum = query.getPageNum() <= 0 ? 1 : query.getPageNum();
        int from = (int) Math.min((pageNum - 1) * pageSize, filtered.size());
        int to = (int) Math.min(from + pageSize, filtered.size());

        PageResult<RateLimitRuleVO> result = new PageResult<>();
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        result.setTotal(filtered.size());
        result.setList(new ArrayList<>(filtered.subList(from, to)));
        return result;
    }

    /** 按 ID 取一条。 */
    public RateLimitRuleVO get(Long id) {
        RateLimitRuleEntity row = gatewayProvider.get().ruleMapper().selectById(id);
        if (row == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "限流规则不存在: " + id);
        }
        return toVO(row);
    }

    /** 新增。 */
    public void create(RateLimitRuleSaveRequest request) {
        ContentGuardGateway gateway = gatewayProvider.get();
        String ruleName = request.getRuleName().trim();
        if (findByName(gateway, ruleName) != null) {
            throw new BizException(ResultCode.PARAM_INVALID, "规则名已存在: " + ruleName);
        }
        RateLimitRuleEntity row = toEntity(request, ruleName);
        long now = System.currentTimeMillis();
        row.setCreatedAtMs(now);
        row.setUpdatedAtMs(now);
        gateway.ruleMapper().insert(row);
        log.info("[CONTENT-GUARD] rate limit rule created, name={}, path={}, limit={}",
            ruleName, row.getPathPrefix(), row.getLimitCount());
    }

    /** 编辑。 */
    public void update(Long id, RateLimitRuleSaveRequest request) {
        ContentGuardGateway gateway = gatewayProvider.get();
        if (gateway.ruleMapper().selectById(id) == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "限流规则不存在: " + id);
        }
        String ruleName = request.getRuleName().trim();
        RateLimitRuleEntity sameName = findByName(gateway, ruleName);
        if (sameName != null && !sameName.getId().equals(id)) {
            throw new BizException(ResultCode.PARAM_INVALID, "规则名已存在: " + ruleName);
        }
        RateLimitRuleEntity row = toEntity(request, ruleName);
        row.setId(id);
        row.setUpdatedAtMs(System.currentTimeMillis());
        gateway.ruleMapper().updateById(row);
        log.info("[CONTENT-GUARD] rate limit rule updated, id={}, name={}", id, ruleName);
    }

    /** 删除。 */
    public void delete(Long id) {
        if (gatewayProvider.get().ruleMapper().deleteById(id) == 0) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "限流规则不存在: " + id);
        }
        log.info("[CONTENT-GUARD] rate limit rule deleted, id={}", id);
    }

    /** 启停。 */
    public void toggle(Long id, boolean enabled) {
        ContentGuardGateway gateway = gatewayProvider.get();
        if (gateway.ruleMapper().selectById(id) == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "限流规则不存在: " + id);
        }
        RateLimitRuleEntity row = new RateLimitRuleEntity();
        row.setId(id);
        row.setEnabled(enabled);
        row.setUpdatedAtMs(System.currentTimeMillis());
        gateway.ruleMapper().updateById(row);
        log.info("[CONTENT-GUARD] rate limit rule toggled, id={}, enabled={}", id, enabled);
    }

    /** 可选维度/算法枚举，供前端下拉直接渲染。 */
    public List<String> dimensions() {
        return Arrays.stream(RateLimitDimension.values()).map(Enum::name).toList();
    }

    public List<String> algorithms() {
        return Arrays.stream(RateLimitAlgorithm.values()).map(Enum::name).toList();
    }

    private RateLimitRuleEntity findByName(ContentGuardGateway gateway, String ruleName) {
        return gateway.ruleMapper().selectOne(
            new QueryWrapper<RateLimitRuleEntity>().eq("rule_name", ruleName).last("LIMIT 1"));
    }

    private boolean matches(RateLimitRuleEntity row, PageQuery query) {
        if (query.getStatus() != null && !Boolean.valueOf(query.getStatus() == 1).equals(row.getEnabled())) {
            return false;
        }
        String keyword = query.getKeyword();
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String lower = keyword.trim().toLowerCase();
        return (row.getRuleName() != null && row.getRuleName().toLowerCase().contains(lower))
            || (row.getPathPrefix() != null && row.getPathPrefix().toLowerCase().contains(lower));
    }

    private RateLimitRuleEntity toEntity(RateLimitRuleSaveRequest request, String ruleName) {
        RateLimitRuleEntity row = new RateLimitRuleEntity();
        row.setRuleName(ruleName);
        row.setPathPrefix(request.getPathPrefix().trim());
        row.setDimension(parseDimension(request.getDimension()).name());
        row.setLimitCount(request.getLimitCount());
        row.setAlgorithm(parseAlgorithm(request.getAlgorithm()).name());
        row.setWindowSeconds(request.getWindowSeconds());
        row.setPriority(request.getPriority());
        row.setEnabled(request.getEnabled() == null || request.getEnabled());
        return row;
    }

    private RateLimitRuleVO toVO(RateLimitRuleEntity row) {
        RateLimitRuleVO vo = new RateLimitRuleVO();
        vo.setId(row.getId());
        vo.setRuleName(row.getRuleName());
        vo.setPathPrefix(row.getPathPrefix());
        vo.setDimension(row.getDimension());
        vo.setLimitCount(row.getLimitCount());
        vo.setAlgorithm(row.getAlgorithm());
        vo.setWindowSeconds(row.getWindowSeconds());
        vo.setPriority(row.getPriority());
        vo.setEnabled(row.getEnabled());
        vo.setCreatedAtMs(row.getCreatedAtMs());
        vo.setUpdatedAtMs(row.getUpdatedAtMs());
        return vo;
    }

    /**
     * 维度/算法解析在后台侧<b>严格报错</b>，不复用 starter 那两个"宽松回落"的 parse——
     * 运行时读到脏数据回落默认值是为了不让一条坏记录拖垮整个限流，而后台是数据入口，
     * 这里静默回落只会让运营以为自己配的是 IP 维度、实际生效的却是 API_KEY。
     */
    private RateLimitDimension parseDimension(String raw) {
        try {
            return RateLimitDimension.valueOf(raw.trim().toUpperCase());
        } catch (RuntimeException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "非法计数维度: " + raw);
        }
    }

    private RateLimitAlgorithm parseAlgorithm(String raw) {
        try {
            return RateLimitAlgorithm.valueOf(raw.trim().toUpperCase());
        } catch (RuntimeException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "非法限流算法: " + raw);
        }
    }
}
