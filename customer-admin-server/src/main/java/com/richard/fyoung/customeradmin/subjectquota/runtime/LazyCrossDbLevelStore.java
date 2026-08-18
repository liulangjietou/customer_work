package com.richard.fyoung.customeradmin.subjectquota.runtime;

import com.richard.fyoung.customeradmin.subjectquota.config.SubjectQuotaGatewayProvider;
import com.richard.fyoung.customerwork.safety.subjectquota.MybatisSubjectQuotaLevelStore;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaLevel;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaLevelStore;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * 等级存储的惰性跨库包装：每次调用才去取门面，且把"库不可达"翻译成"读取失败"。
 *
 * <p><b>为什么不能直接把跨库 Store 交给 Provider</b>：Provider 在构造时就会 reload 一次，
 * 而 admin 的铁律是<b>启动期绝不触碰客服端库</b>——后台不该因为客服端库没起来就启动不了。
 * 这里把异常吞成 {@code Optional.empty()}，Provider 据此保留空快照并打 error 日志，
 * 启动继续；等级会在后续的惰性刷新里补上。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class LazyCrossDbLevelStore implements SubjectQuotaLevelStore {

    private final SubjectQuotaGatewayProvider gatewayProvider;

    public LazyCrossDbLevelStore(SubjectQuotaGatewayProvider gatewayProvider) {
        this.gatewayProvider = gatewayProvider;
    }

    private Optional<SubjectQuotaLevelStore> delegate() {
        try {
            return Optional.of(new MybatisSubjectQuotaLevelStore(gatewayProvider.get().levelMapper()));
        } catch (Exception e) {
            log.error("subject quota level store unavailable (cross-db), code={}",
                "SQUOTA-ADMIN-LEVEL-DS-UNAVAILABLE", e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<List<SubjectQuotaLevel>> findAllEnabled() {
        return delegate().flatMap(SubjectQuotaLevelStore::findAllEnabled);
    }

    @Override
    public List<SubjectQuotaLevel> findByTenant(String tenantId) {
        return delegate().map(store -> store.findByTenant(tenantId)).orElseGet(List::of);
    }

    @Override
    public void save(SubjectQuotaLevel level) {
        // 写路径不吞异常：后台点了保存却什么都没发生，比报一个错糟糕得多
        new MybatisSubjectQuotaLevelStore(gatewayProvider.get().levelMapper()).save(level);
    }

    @Override
    public void delete(String tenantId, String levelCode) {
        new MybatisSubjectQuotaLevelStore(gatewayProvider.get().levelMapper()).delete(tenantId, levelCode);
    }

    @Override
    public Optional<String> fingerprint() {
        return delegate().flatMap(SubjectQuotaLevelStore::fingerprint);
    }
}
