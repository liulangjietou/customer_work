package com.richard.fyoung.customeradmin.contentguard.runtime;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardGatewayProvider;
import com.richard.fyoung.customerwork.sensitiveword.SensitiveWord;
import com.richard.fyoung.customerwork.sensitiveword.SensitiveWordAction;
import com.richard.fyoung.customerwork.sensitiveword.SensitiveWordCategory;
import com.richard.fyoung.customerwork.sensitiveword.SensitiveWordStore;
import com.richard.fyoung.customerwork.sensitiveword.entity.SensitiveWordEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * admin 侧的敏感词表读取实现：每次调用才经 {@link ContentGuardGatewayProvider} 取门面。
 *
 * <p><b>为什么不直接注入 Mapper</b>：门面是惰性的，注入 Mapper 就等于在 admin 启动期建连接池、连客服端库，
 * 而"客服端库没起来 admin 也要能启动"是这套跨库访问的既定前提。每次调用走一次 {@code get()} 的开销
 * 只是一次 volatile 读（构建成功后缓存），可以忽略。</p>
 *
 * <p>读失败一律返回 {@code Optional.empty()}，把 fail-closed 的判断权交回
 * {@code SensitiveWordFilter}——别在这一层擅自"降级成空词表"，那等于静默放行。</p>
 * @author owlzhangfq@gmail.com
 */
public class GatewaySensitiveWordStore implements SensitiveWordStore {

    private static final Logger log = LoggerFactory.getLogger(GatewaySensitiveWordStore.class);

    private final ContentGuardGatewayProvider gatewayProvider;

    public GatewaySensitiveWordStore(ContentGuardGatewayProvider gatewayProvider) {
        this.gatewayProvider = gatewayProvider;
    }

    @Override
    public List<SensitiveWord> findAll() {
        try {
            return toDomainList(gatewayProvider.get().wordMapper().selectList(null));
        } catch (Exception e) {
            log.error("[CONTENT-GUARD] admin word findAll failed, code={}", "CONTENTGUARD-WORD-FINDALL-FAIL", e);
            return List.of();
        }
    }

    @Override
    public Optional<List<SensitiveWord>> findEnabled() {
        try {
            QueryWrapper<SensitiveWordEntity> wrapper = new QueryWrapper<SensitiveWordEntity>().eq("enabled", true);
            return Optional.of(toDomainList(gatewayProvider.get().wordMapper().selectList(wrapper)));
        } catch (Exception e) {
            log.error("[CONTENT-GUARD] admin word findEnabled failed, code={}",
                "CONTENTGUARD-WORD-FINDENABLED-FAIL", e);
            return Optional.empty();
        }
    }

    @Override
    public void save(SensitiveWord word) {
        // admin 的写入走 SensitiveWordService（带判重与操作日志），运行时这条链路只读
        throw new UnsupportedOperationException("admin runtime store is read-only, use SensitiveWordService to write");
    }

    @Override
    public Optional<String> fingerprint() {
        try {
            return Optional.of(String.valueOf(gatewayProvider.get().wordMapper().selectFingerprint()));
        } catch (Exception e) {
            log.error("[CONTENT-GUARD] admin word fingerprint failed, code={}",
                "CONTENTGUARD-WORD-FINGERPRINT-FAIL", e);
            return Optional.empty();
        }
    }

    private List<SensitiveWord> toDomainList(List<SensitiveWordEntity> rows) {
        List<SensitiveWord> result = new ArrayList<>(rows.size());
        for (SensitiveWordEntity row : rows) {
            result.add(new SensitiveWord(row.getId(), row.getWord(),
                SensitiveWordCategory.fromName(row.getCategory()),
                SensitiveWordAction.valueOf(row.getAction()),
                row.getEnabled() != null && row.getEnabled()));
        }
        return result;
    }
}
