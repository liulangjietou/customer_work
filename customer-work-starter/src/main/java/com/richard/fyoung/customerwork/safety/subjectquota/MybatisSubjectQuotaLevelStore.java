package com.richard.fyoung.customerwork.safety.subjectquota;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customerwork.safety.subjectquota.entity.SubjectQuotaLevelDO;
import com.richard.fyoung.customerwork.safety.subjectquota.mapper.SubjectQuotaLevelMapper;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * 等级的 MyBatis-Plus 实现。
 *
 * <p>全部查询走 {@link CrossTenantOperations}：快照加载发生在<b>定时任务</b>里（没有租户上下文），
 * 且一个进程要同时服务所有租户，本就该把各租户的等级一次全取回来按 (租户, 等级码) 索引。
 * 后台跨租户维护同理——两类调用方共用一个 Store，让 Store 自己按显式 tenantId 取数，
 * 比让调用方各自记得切上下文更不容易出错（同 {@code MybatisTenantQuotaStore}）。</p>
 *
 * <p>异常一律 {@code catch(Exception)}：HikariPool / MyBatis 初始化异常是 RuntimeException，
 * 不是 SQLException 子类。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisSubjectQuotaLevelStore implements SubjectQuotaLevelStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisSubjectQuotaLevelStore.class);

    private final SubjectQuotaLevelMapper mapper;

    public MybatisSubjectQuotaLevelStore(SubjectQuotaLevelMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<List<SubjectQuotaLevel>> findAllEnabled() {
        try {
            List<SubjectQuotaLevelDO> rows = CrossTenantOperations.execute(() -> mapper.selectList(
                new LambdaQueryWrapper<SubjectQuotaLevelDO>().eq(SubjectQuotaLevelDO::getEnabled, 1)));
            return Optional.of(rows.stream().map(MybatisSubjectQuotaLevelStore::toDomain).toList());
        } catch (Exception e) {
            // 读失败返回 empty：Provider 据此保留上次快照 / 回退内置档，绝不把"读不到"当成"限死一切"
            log.error("subject quota level findAllEnabled failed, code={}", "SQUOTA-LEVEL-LOAD-FAIL", e);
            return Optional.empty();
        }
    }

    @Override
    public List<SubjectQuotaLevel> findByTenant(String tenantId) {
        try {
            List<SubjectQuotaLevelDO> rows = CrossTenantOperations.execute(() -> mapper.selectList(
                new LambdaQueryWrapper<SubjectQuotaLevelDO>()
                    .eq(SubjectQuotaLevelDO::getTenantId, tenantId)
                    .orderByAsc(SubjectQuotaLevelDO::getLevelCode)));
            return rows.stream().map(MybatisSubjectQuotaLevelStore::toDomain).toList();
        } catch (Exception e) {
            log.error("subject quota level findByTenant failed, code={}, tenant={}",
                "SQUOTA-LEVEL-QUERY-FAIL", tenantId, e);
            return List.of();
        }
    }

    @Override
    public void save(SubjectQuotaLevel level) {
        long now = System.currentTimeMillis();
        CrossTenantOperations.run(() -> {
            SubjectQuotaLevelDO existing = mapper.selectOne(new LambdaQueryWrapper<SubjectQuotaLevelDO>()
                .eq(SubjectQuotaLevelDO::getTenantId, level.tenantId())
                .eq(SubjectQuotaLevelDO::getLevelCode, level.levelCode()));

            SubjectQuotaLevelDO row = existing == null ? new SubjectQuotaLevelDO() : existing;
            row.setTenantId(level.tenantId());
            row.setLevelCode(level.levelCode());
            row.setLevelName(level.levelName());
            row.setSubjectType(level.subjectType().name());
            row.setWindowSeconds(level.effectiveWindowSeconds());
            row.setTokenLimit(level.tokenLimit());
            row.setRequestLimit(level.requestLimit());
            row.setExceedAction(level.exceedAction().name());
            row.setEnabled(level.enabled() ? 1 : 0);
            row.setRemark(level.remark());
            row.setUpdatedAtMs(now);
            if (existing == null) {
                row.setCreatedAtMs(now);
                mapper.insert(row);
            } else {
                mapper.updateById(row);
            }
        });
    }

    @Override
    public void delete(String tenantId, String levelCode) {
        CrossTenantOperations.run(() -> mapper.delete(new LambdaQueryWrapper<SubjectQuotaLevelDO>()
            .eq(SubjectQuotaLevelDO::getTenantId, tenantId)
            .eq(SubjectQuotaLevelDO::getLevelCode, levelCode)));
    }

    @Override
    public Optional<String> fingerprint() {
        try {
            return Optional.ofNullable(CrossTenantOperations.execute(mapper::selectFingerprint));
        } catch (Exception e) {
            log.error("subject quota level fingerprint failed, code={}", "SQUOTA-LEVEL-FINGERPRINT-FAIL", e);
            return Optional.empty();
        }
    }

    private static SubjectQuotaLevel toDomain(SubjectQuotaLevelDO row) {
        return new SubjectQuotaLevel(
            row.getId(),
            row.getTenantId(),
            row.getLevelCode(),
            row.getLevelName(),
            QuotaSubjectType.parse(row.getSubjectType()),
            row.getWindowSeconds() == null ? SubjectQuotaLevel.DEFAULT_WINDOW_SECONDS : row.getWindowSeconds(),
            row.getTokenLimit() == null ? 0L : row.getTokenLimit(),
            row.getRequestLimit() == null ? 0 : row.getRequestLimit(),
            SubjectExceedAction.parse(row.getExceedAction()),
            row.getEnabled() == null || row.getEnabled() == 1,
            row.getRemark());
    }
}
