package com.richard.fyoung.customerwork.core.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customerwork.core.memory.entity.LongTermMemoryDO;
import com.richard.fyoung.customerwork.core.memory.mapper.LongTermMemoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * MyBatis-Plus 长期记忆存储（默认实现：{@code customer-work.memory.store-mode=jdbc} 时装配）。
 *
 * <p>把 L2 长期记忆结构化写入 {@code cw_long_term_memory} 表，保证应用重启 / 多副本部署下记忆不丢
 * （进程内 {@link InMemoryLongTermMemoryStore} 重启即清空、副本之间还各存各的）。</p>
 *
 * <p><b>召回为何不在 SQL 里做</b>：打分是字符重合度（{@link FactRelevanceScorer}），SQL 表达不了，
 * 故先按写入顺序倒序取一个有上限的候选集再在 Java 侧打分。上限由
 * {@code customer-work.memory.recall-scan-limit} 控制——不设上限的话，长期积累的分区会把整表拉进内存。
 * 真正的语义召回请切 {@code memory.provider=bailian/mem0/reme}。</p>
 *
 * <p>全部方法失败只记 error 不抛异常：长期记忆是增强能力，DB 抖动不该打断对话主链路
 * （读失败退化为"没召回到记忆"，写失败退化为"这条没记住"）。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisLongTermMemoryStore implements LongTermMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisLongTermMemoryStore.class);

    private final LongTermMemoryMapper mapper;
    private final int recallScanLimit;

    public MybatisLongTermMemoryStore(LongTermMemoryMapper mapper, int recallScanLimit) {
        this.mapper = mapper;
        this.recallScanLimit = recallScanLimit > 0 ? recallScanLimit : 1;
    }

    @Override
    public void add(String scopeId, String fact) {
        if (fact == null || fact.isBlank()) {
            return;
        }
        String trimmed = fact.trim();
        try {
            LongTermMemoryDO record = new LongTermMemoryDO();
            record.setScopeId(scopeId);
            record.setFact(trimmed);
            record.setScopeHash(hash(scopeId, trimmed));
            record.setCreatedAtMs(System.currentTimeMillis());
            mapper.insertIfAbsent(record);
        } catch (Exception e) {
            log.error("[MybatisLongTermMemoryStore] add failed, errorCode={}, scopeId={}",
                "LTM-STORE-ADD-FAIL", scopeId, e);
        }
    }

    @Override
    public List<String> recall(String scopeId, String query, int topK) {
        try {
            return FactRelevanceScorer.topMatches(
                mapper.selectRecentFacts(scopeId, recallScanLimit), query, topK);
        } catch (Exception e) {
            log.error("[MybatisLongTermMemoryStore] recall failed, errorCode={}, scopeId={}",
                "LTM-STORE-RECALL-FAIL", scopeId, e);
            return List.of();
        }
    }

    @Override
    public void clear(String scopeId) {
        try {
            mapper.delete(new LambdaQueryWrapper<LongTermMemoryDO>().eq(LongTermMemoryDO::getScopeId, scopeId));
        } catch (Exception e) {
            log.error("[MybatisLongTermMemoryStore] clear failed, errorCode={}, scopeId={}",
                "LTM-STORE-CLEAR-FAIL", scopeId, e);
        }
    }

    @Override
    public int size(String scopeId) {
        try {
            Long count = mapper.selectCount(
                new LambdaQueryWrapper<LongTermMemoryDO>().eq(LongTermMemoryDO::getScopeId, scopeId));
            return count == null ? 0 : count.intValue();
        } catch (Exception e) {
            log.error("[MybatisLongTermMemoryStore] size failed, errorCode={}, scopeId={}",
                "LTM-STORE-SIZE-FAIL", scopeId, e);
            return 0;
        }
    }

    /**
     * 去重键：{@code scopeId + '\n' + fact} 的 SHA-256 十六进制串。
     *
     * <p>分隔符用换行而非直接拼接，避免 {@code ("ab","c")} 与 {@code ("a","bc")} 撞出同一个哈希。
     * scope 也进哈希是因为唯一索引建在 {@code (tenant_id, scope_hash)} 上——不含 scope 的话，
     * 同租户下不同分区的相同事实会被误判为重复而只留一条。</p>
     */
    private static String hash(String scopeId, String fact) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(((scopeId == null ? "" : scopeId) + "\n" + fact)
                .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，走不到这里；真走到了说明 JRE 被裁剪过，属于部署事故
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
