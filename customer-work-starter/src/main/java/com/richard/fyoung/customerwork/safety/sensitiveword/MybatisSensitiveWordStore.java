package com.richard.fyoung.customerwork.safety.sensitiveword;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customerwork.safety.sensitiveword.entity.SensitiveWordEntity;
import com.richard.fyoung.customerwork.safety.sensitiveword.mapper.SensitiveWordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis-Plus 敏感词表存储（生产实现：{@code sensitive-word.store-mode=jdbc} 时装配）。
 *
 * <p>词表落 {@code cw_sensitive_word} 表，多实例共享、支持后台运营维护。建表与种子由统一
 * Flyway 负责，本类只表达读写；DO ↔ 领域对象转换收敛在本层。异常一律
 * {@code catch(Exception)}（HikariPool 初始化异常是 RuntimeException，非 SQLException 子类）。</p>
 *
 * <p><b>读成败必须可区分</b>：{@link #findEnabled()} 读失败返回 {@code Optional.empty()}（而非空集合），
 * 让上层 {@link SensitiveWordFilter} 能把"读失败"与"确实没词"分流处理——绝不把 DB 抖动误判成"放行"。
 * {@link #findAll()} 仅供运营展示，读失败降级空集合无安全含义。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisSensitiveWordStore implements SensitiveWordStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisSensitiveWordStore.class);

    private final SensitiveWordMapper mapper;

    public MybatisSensitiveWordStore(SensitiveWordMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<SensitiveWord> findAll() {
        try {
            return toDomainList(mapper.selectList(null));
        } catch (Exception e) {
            log.error("[MybatisSensitiveWordStore] findAll failed, code={}", "SENSITIVE-STORE-FINDALL-FAIL", e);
            return List.of();
        }
    }

    @Override
    public Optional<List<SensitiveWord>> findEnabled() {
        try {
            QueryWrapper<SensitiveWordEntity> wrapper = new QueryWrapper<SensitiveWordEntity>().eq("enabled", true);
            return Optional.of(toDomainList(mapper.selectList(wrapper)));
        } catch (Exception e) {
            // 读失败返回 empty（非空集合）：让 SensitiveWordFilter 走 fail-closed 分支，不静默放行
            log.error("[MybatisSensitiveWordStore] findEnabled failed, code={}", "SENSITIVE-STORE-FINDENABLED-FAIL", e);
            return Optional.empty();
        }
    }

    @Override
    public void save(SensitiveWord word) {
        if (word == null || word.getWord() == null || word.getWord().isEmpty()) {
            return;
        }
        try {
            mapper.upsert(toDO(word));
        } catch (Exception e) {
            log.error("[MybatisSensitiveWordStore] save failed, code={}, word={}",
                "SENSITIVE-STORE-SAVE-FAIL", word.getWord(), e);
            throw new IllegalStateException("failed to save sensitive word: " + word.getWord(), e);
        }
    }

    /**
     * 覆写为单行聚合 SQL：刷新器每轮只需判断"变没变"，不必把整张词表拉回进程再算 hash。
     * 读失败返回 {@code Optional.empty()}，刷新器据此跳过本轮重建、保留已加载的好词表。
     */
    @Override
    public Optional<String> fingerprint() {
        try {
            return Optional.of(String.valueOf(mapper.selectFingerprint()));
        } catch (Exception e) {
            log.error("[MybatisSensitiveWordStore] fingerprint failed, code={}", "SENSITIVE-STORE-FINGERPRINT-FAIL", e);
            return Optional.empty();
        }
    }

    private List<SensitiveWord> toDomainList(List<SensitiveWordEntity> rows) {
        List<SensitiveWord> result = new ArrayList<>(rows.size());
        for (SensitiveWordEntity row : rows) {
            result.add(toDomain(row));
        }
        return result;
    }

    private SensitiveWord toDomain(SensitiveWordEntity row) {
        return new SensitiveWord(
            row.getId(),
            row.getWord(),
            SensitiveWordCategory.fromName(row.getCategory()),
            SensitiveWordAction.valueOf(row.getAction()),
            row.getEnabled() != null && row.getEnabled());
    }

    private SensitiveWordEntity toDO(SensitiveWord word) {
        SensitiveWordEntity row = new SensitiveWordEntity();
        row.setId(word.getId());
        row.setWord(word.getWord());
        row.setCategory(word.getCategory().name());
        row.setAction(word.getAction().name());
        row.setEnabled(word.isEnabled());
        long now = System.currentTimeMillis();
        row.setCreatedAtMs(now);
        row.setUpdatedAtMs(now);
        return row;
    }
}
