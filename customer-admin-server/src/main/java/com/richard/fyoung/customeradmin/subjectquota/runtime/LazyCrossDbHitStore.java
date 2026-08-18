package com.richard.fyoung.customeradmin.subjectquota.runtime;

import com.richard.fyoung.customeradmin.subjectquota.config.SubjectQuotaGatewayProvider;
import com.richard.fyoung.customerwork.safety.subjectquota.MybatisSubjectQuotaHitStore;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaHit;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaHitRank;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaHitStore;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 命中记录存储的惰性跨库包装（理由同 {@link LazyCrossDbLevelStore}）。
 *
 * <p>写入失败只记日志：命中记录是观测数据，为它让一个"本来就要被拒"的请求再抛一个错，
 * 是把观测手段变成了故障源。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class LazyCrossDbHitStore implements SubjectQuotaHitStore {

    private final SubjectQuotaGatewayProvider gatewayProvider;

    public LazyCrossDbHitStore(SubjectQuotaGatewayProvider gatewayProvider) {
        this.gatewayProvider = gatewayProvider;
    }

    @Override
    public void record(SubjectQuotaHit hit) {
        try {
            new MybatisSubjectQuotaHitStore(gatewayProvider.get().hitMapper()).record(hit);
        } catch (Exception e) {
            log.error("admin subject quota hit record failed, code={}, subject={}:{}",
                "SQUOTA-ADMIN-HIT-FAIL", hit.subjectType(), hit.subjectId(), e);
        }
    }

    @Override
    public List<SubjectQuotaHit> findRecent(String tenantId, long sinceMs, int limit) {
        try {
            return new MybatisSubjectQuotaHitStore(gatewayProvider.get().hitMapper())
                .findRecent(tenantId, sinceMs, limit);
        } catch (Exception e) {
            log.error("admin subject quota hit query failed, code={}", "SQUOTA-ADMIN-HIT-QUERY-FAIL", e);
            return List.of();
        }
    }

    @Override
    public List<SubjectQuotaHitRank> rank(String tenantId, long sinceMs, int limit) {
        try {
            return new MybatisSubjectQuotaHitStore(gatewayProvider.get().hitMapper())
                .rank(tenantId, sinceMs, limit);
        } catch (Exception e) {
            log.error("admin subject quota hit rank failed, code={}", "SQUOTA-ADMIN-HIT-RANK-FAIL", e);
            return List.of();
        }
    }
}
