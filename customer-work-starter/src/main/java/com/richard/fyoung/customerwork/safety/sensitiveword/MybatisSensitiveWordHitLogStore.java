package com.richard.fyoung.customerwork.safety.sensitiveword;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customerwork.safety.sensitiveword.entity.SensitiveWordHitLogEntity;
import com.richard.fyoung.customerwork.safety.sensitiveword.mapper.SensitiveWordHitLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * MyBatis-Plus 命中日志存储（{@code sensitive-word.hit-log.store-mode=jdbc} 时装配）。
 *
 * <p>落 {@code cw_sensitive_word_hit_log} 表，供后台命中看板跨实例查询。建表由统一
 * Flyway 负责。异常一律 {@code catch(Exception)} 并<b>吞掉不抛</b>——
 * 调用方是后台异步线程，抛出去只会打日志，而命中日志写失败不该有任何主链路后果。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisSensitiveWordHitLogStore implements SensitiveWordHitLogStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisSensitiveWordHitLogStore.class);

    private static final String SEPARATOR = ",";

    private final SensitiveWordHitLogMapper mapper;

    public MybatisSensitiveWordHitLogStore(SensitiveWordHitLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(SensitiveWordHitRecord record) {
        if (record == null) {
            return;
        }
        try {
            mapper.insert(toDO(record));
        } catch (Exception e) {
            log.error("[SensitiveWordHitLogStore] save failed, code={}, direction={}",
                "SENSITIVE-HITLOG-SAVE-FAIL", record.direction(), e);
        }
    }

    @Override
    public List<SensitiveWordHitRecord> findRecent(int limit) {
        try {
            QueryWrapper<SensitiveWordHitLogEntity> wrapper = new QueryWrapper<SensitiveWordHitLogEntity>()
                .orderByDesc("id").last("LIMIT " + Math.max(1, limit));
            return toDomainList(mapper.selectList(wrapper));
        } catch (Exception e) {
            log.error("[SensitiveWordHitLogStore] findRecent failed, code={}", "SENSITIVE-HITLOG-QUERY-FAIL", e);
            return List.of();
        }
    }

    private List<SensitiveWordHitRecord> toDomainList(List<SensitiveWordHitLogEntity> rows) {
        List<SensitiveWordHitRecord> result = new ArrayList<>(rows.size());
        for (SensitiveWordHitLogEntity row : rows) {
            result.add(toDomain(row));
        }
        return result;
    }

    private SensitiveWordHitRecord toDomain(SensitiveWordHitLogEntity row) {
        return new SensitiveWordHitRecord(
            SensitiveWordHitDirection.valueOf(row.getDirection()),
            SensitiveWordAction.valueOf(row.getAction()),
            splitToList(row.getWords()),
            splitToList(row.getCategories()),
            row.getAgentName(),
            row.getSessionId(),
            row.getUserId(),
            row.getSnippet(),
            row.getCreatedAtMs() == null ? 0L : row.getCreatedAtMs());
    }

    private SensitiveWordHitLogEntity toDO(SensitiveWordHitRecord record) {
        SensitiveWordHitLogEntity row = new SensitiveWordHitLogEntity();
        row.setDirection(record.direction().name());
        row.setAction(record.action() == null ? SensitiveWordAction.REVIEW.name() : record.action().name());
        row.setWords(join(record.words()));
        row.setCategories(join(record.categories()));
        row.setHitCount(CollectionUtils.isEmpty(record.words()) ? 0 : record.words().size());
        row.setAgentName(record.agentName());
        row.setSessionId(record.sessionId());
        row.setUserId(record.userId());
        row.setSnippet(record.snippet());
        row.setCreatedAtMs(record.createdAtMs());
        return row;
    }

    private static String join(List<String> values) {
        return CollectionUtils.isEmpty(values) ? "" : String.join(SEPARATOR, values);
    }

    private static List<String> splitToList(String raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(raw.split(SEPARATOR));
    }
}
