package com.richard.fyoung.customerwork.safety.subjectquota;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customerwork.safety.subjectquota.entity.SubjectQuotaHitDO;
import com.richard.fyoung.customerwork.safety.subjectquota.mapper.SubjectQuotaHitMapper;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 命中记录的 MyBatis-Plus 实现。
 *
 * <p>写入失败只记日志不抛：这条记录是观测数据，为了它让一个"本来就要被拒"的请求
 * 再抛一个 500，是把观测手段变成了故障源。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisSubjectQuotaHitStore implements SubjectQuotaHitStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisSubjectQuotaHitStore.class);

    private final SubjectQuotaHitMapper mapper;

    public MybatisSubjectQuotaHitStore(SubjectQuotaHitMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void record(SubjectQuotaHit hit) {
        try {
            CrossTenantOperations.run(() -> mapper.insert(toDO(hit)));
        } catch (Exception e) {
            log.error("subject quota hit record failed, code={}, subject={}:{}",
                "SQUOTA-HIT-RECORD-FAIL", hit.subjectType(), hit.subjectId(), e);
        }
    }

    @Override
    public List<SubjectQuotaHit> findRecent(String tenantId, long sinceMs, int limit) {
        try {
            List<SubjectQuotaHitDO> rows = CrossTenantOperations.execute(() -> mapper.selectList(
                new LambdaQueryWrapper<SubjectQuotaHitDO>()
                    .eq(SubjectQuotaHitDO::getTenantId, tenantId)
                    .ge(SubjectQuotaHitDO::getCreatedAtMs, sinceMs)
                    .orderByDesc(SubjectQuotaHitDO::getCreatedAtMs)
                    .last("LIMIT " + Math.max(1, limit))));
            return rows.stream().map(MybatisSubjectQuotaHitStore::toDomain).toList();
        } catch (Exception e) {
            log.error("subject quota hit query failed, code={}, tenant={}",
                "SQUOTA-HIT-QUERY-FAIL", tenantId, e);
            return List.of();
        }
    }

    @Override
    public List<SubjectQuotaHitRank> rank(String tenantId, long sinceMs, int limit) {
        try {
            return CrossTenantOperations.execute(
                () -> mapper.selectRank(tenantId, sinceMs, Math.max(1, limit)));
        } catch (Exception e) {
            log.error("subject quota hit rank failed, code={}, tenant={}",
                "SQUOTA-HIT-RANK-FAIL", tenantId, e);
            return List.of();
        }
    }

    private static SubjectQuotaHitDO toDO(SubjectQuotaHit hit) {
        SubjectQuotaHitDO row = new SubjectQuotaHitDO();
        row.setTenantId(hit.tenantId());
        row.setSubjectType(hit.subjectType().name());
        row.setSubjectId(hit.subjectId());
        row.setLevelCode(hit.levelCode());
        row.setLimitKind(hit.limitKind() == null ? null : hit.limitKind().name());
        row.setUsed(hit.used());
        row.setLimitValue(hit.limitValue());
        row.setWindowSeconds(hit.windowSeconds());
        row.setAction(hit.action() == null ? null : hit.action().name());
        row.setResource(hit.resource());
        row.setCreatedAtMs(hit.createdAtMs());
        return row;
    }

    private static SubjectQuotaHit toDomain(SubjectQuotaHitDO row) {
        return new SubjectQuotaHit(
            row.getId(),
            row.getTenantId(),
            QuotaSubjectType.parse(row.getSubjectType()),
            row.getSubjectId(),
            row.getLevelCode(),
            parseKind(row.getLimitKind()),
            row.getUsed() == null ? 0L : row.getUsed(),
            row.getLimitValue() == null ? 0L : row.getLimitValue(),
            row.getWindowSeconds() == null ? 0 : row.getWindowSeconds(),
            SubjectExceedAction.parse(row.getAction()),
            row.getResource(),
            row.getCreatedAtMs() == null ? 0L : row.getCreatedAtMs());
    }

    /** 脏值按 REQUEST 兜底：次数是更常见的触顶维度，落错只影响这条记录的展示分类。 */
    private static SubjectQuotaDecision.LimitKind parseKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return SubjectQuotaDecision.LimitKind.REQUEST;
        }
        try {
            return SubjectQuotaDecision.LimitKind.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return SubjectQuotaDecision.LimitKind.REQUEST;
        }
    }
}
