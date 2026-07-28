package com.richard.fyoung.customeradmin.contentguard.service;

import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardGatewayProvider;
import com.richard.fyoung.customeradmin.contentguard.dto.ContentGuardCountVO;
import com.richard.fyoung.customeradmin.contentguard.dto.SensitiveWordHitLogPageQuery;
import com.richard.fyoung.customeradmin.contentguard.dto.SensitiveWordHitLogVO;
import com.richard.fyoung.customeradmin.contentguard.dto.SensitiveWordHitStatsVO;
import com.richard.fyoung.customeradmin.contentguard.jdbc.ContentGuardCountRow;
import com.richard.fyoung.customeradmin.contentguard.jdbc.ContentGuardGateway;
import com.richard.fyoung.customeradmin.contentguard.jdbc.SensitiveWordHitLogExtMapper;
import com.richard.fyoung.customeradmin.contentguard.jdbc.SensitiveWordHitLogQueryParam;
import com.richard.fyoung.customerwork.sensitiveword.entity.SensitiveWordHitLogEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 敏感词命中看板：明细分页 + 四组统计（动作分布 / 方向分布 / Top 命中词 / 时间趋势）。
 *
 * <p>明细与统计共用同一套筛选条件，保证图表和列表永远在讲同一批数据——两者用不同条件是看板最常见的坑。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class SensitiveWordHitLogService {

    /** Top 命中词取前 10：再多前端图表也塞不下，运营真正关心的也就头部几个。 */
    private static final int TOP_WORDS = 10;

    /** 趋势按小时聚合的区间上限（毫秒）：跨度超过 2 天就改按天，否则 X 轴点数爆炸。 */
    private static final long HOURLY_TREND_MAX_SPAN_MS = 2L * 24 * 60 * 60 * 1000;

    private static final String FORMAT_HOUR = "%Y-%m-%d %H:00";
    private static final String FORMAT_DAY = "%Y-%m-%d";
    private static final String GRANULARITY_HOUR = "hour";
    private static final String GRANULARITY_DAY = "day";

    private static final String SEPARATOR = ",";

    private final ContentGuardGatewayProvider gatewayProvider;

    public SensitiveWordHitLogService(ContentGuardGatewayProvider gatewayProvider) {
        this.gatewayProvider = gatewayProvider;
    }

    /** 命中明细分页。 */
    public PageResult<SensitiveWordHitLogVO> page(SensitiveWordHitLogPageQuery query) {
        ContentGuardGateway gateway = gatewayProvider.get();
        SensitiveWordHitLogQueryParam param = toParam(query);
        long total = gateway.hitLogExtMapper().countBy(param);
        List<SensitiveWordHitLogVO> list = new ArrayList<>();
        if (total > 0) {
            for (SensitiveWordHitLogEntity row : gateway.hitLogExtMapper().findPage(param)) {
                list.add(toVO(row));
            }
        }
        PageResult<SensitiveWordHitLogVO> result = new PageResult<>();
        result.setPageNum(query.getPageNum());
        result.setPageSize(query.getPageSize());
        result.setTotal(total);
        result.setList(list);
        return result;
    }

    /** 看板统计。 */
    public SensitiveWordHitStatsVO stats(SensitiveWordHitLogPageQuery query) {
        SensitiveWordHitLogExtMapper mapper = gatewayProvider.get().hitLogExtMapper();
        SensitiveWordHitLogQueryParam param = toParam(query);
        boolean hourly = isHourlyGranularity(query);

        SensitiveWordHitStatsVO stats = new SensitiveWordHitStatsVO();
        stats.setTotal(mapper.countBy(param));
        stats.setByAction(toCountVOList(mapper.countByAction(param)));
        stats.setByDirection(toCountVOList(mapper.countByDirection(param)));
        stats.setTopWords(toCountVOList(mapper.topWords(param, TOP_WORDS)));
        stats.setTrend(toCountVOList(mapper.trend(param, hourly ? FORMAT_HOUR : FORMAT_DAY)));
        stats.setTrendGranularity(hourly ? GRANULARITY_HOUR : GRANULARITY_DAY);
        return stats;
    }

    /**
     * 趋势粒度：查询区间在 2 天内按小时，否则按天。
     *
     * <p>粒度由后端根据区间决定而非让前端传——前端传粒度必然出现"选了 30 天却按小时"的 720 个点，
     * 图画不出来还拖垮 SQL。</p>
     */
    private boolean isHourlyGranularity(SensitiveWordHitLogPageQuery query) {
        Long start = query.getStartMs();
        Long end = query.getEndMs();
        if (start == null || end == null) {
            return false;
        }
        return end - start <= HOURLY_TREND_MAX_SPAN_MS;
    }

    private SensitiveWordHitLogQueryParam toParam(SensitiveWordHitLogPageQuery query) {
        SensitiveWordHitLogQueryParam param = new SensitiveWordHitLogQueryParam();
        param.setDirection(query.getDirection());
        param.setAction(query.getAction());
        param.setKeyword(query.getKeyword());
        param.setSessionId(query.getSessionId());
        param.setStartMs(query.getStartMs());
        param.setEndMs(query.getEndMs());
        long pageSize = query.getPageSize() <= 0 ? 10 : query.getPageSize();
        long pageNum = query.getPageNum() <= 0 ? 1 : query.getPageNum();
        param.setLimit((int) pageSize);
        param.setOffset((int) ((pageNum - 1) * pageSize));
        return param;
    }

    private List<ContentGuardCountVO> toCountVOList(List<ContentGuardCountRow> rows) {
        List<ContentGuardCountVO> list = new ArrayList<>(rows.size());
        for (ContentGuardCountRow row : rows) {
            list.add(new ContentGuardCountVO(row.getLabel(), row.getTotal()));
        }
        return list;
    }

    private SensitiveWordHitLogVO toVO(SensitiveWordHitLogEntity row) {
        SensitiveWordHitLogVO vo = new SensitiveWordHitLogVO();
        vo.setId(row.getId());
        vo.setDirection(row.getDirection());
        vo.setAction(row.getAction());
        vo.setWords(splitToList(row.getWords()));
        vo.setCategories(splitToList(row.getCategories()));
        vo.setHitCount(row.getHitCount());
        vo.setAgentName(row.getAgentName());
        vo.setSessionId(row.getSessionId());
        vo.setUserId(row.getUserId());
        vo.setSnippet(row.getSnippet());
        vo.setCreatedAtMs(row.getCreatedAtMs());
        return vo;
    }

    private List<String> splitToList(String raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(raw.split(SEPARATOR));
    }
}
