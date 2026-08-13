package com.richard.fyoung.customerwork.core.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customerwork.core.memory.entity.HarnessMemoryDO;
import com.richard.fyoung.customerwork.core.memory.mapper.HarnessMemoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * MyBatis-Plus Harness 分层记忆存储（默认实现：{@code customer-work.harness.memory-store-mode=jdbc} 时装配）。
 *
 * <p>把 {@code MEMORY.md} 全文写入 {@code cw_harness_memory} 表，一个 workspace 恒定一行（upsert 覆盖）。
 * 换机 / 重启 / 清理 workspace 后仍能由 {@link HarnessMemorySyncService} 水合回来。</p>
 *
 * <p>本类<b>不做异常兜底</b>：同步链路的唯一兜底点在 {@link HarnessMemorySyncService}，
 * 两层都吞异常会让真实故障彻底消失在日志里（照 admin 侧 {@code AgentMemoryStore} 的分工）。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisHarnessMemoryStore implements HarnessMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisHarnessMemoryStore.class);

    private final HarnessMemoryMapper mapper;

    public MybatisHarnessMemoryStore(HarnessMemoryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<String> load(String scopeId) {
        HarnessMemoryDO row = mapper.selectOne(byScope(scopeId));
        return row == null ? Optional.empty() : Optional.ofNullable(row.getContent());
    }

    @Override
    public void save(String scopeId, String content) {
        HarnessMemoryDO record = new HarnessMemoryDO();
        record.setScopeId(scopeId);
        record.setScopeHash(hash(scopeId));
        record.setContent(content == null ? "" : content);
        record.setUpdatedAtMs(System.currentTimeMillis());
        mapper.upsert(record);
        log.info("harness memory saved: scopeId={} bytes={}", scopeId, record.getContent().length());
    }

    @Override
    public void delete(String scopeId) {
        mapper.delete(byScope(scopeId));
    }

    /** 按 scope_hash 定位（而非 scope_id）：与唯一索引一致，长路径也走得到索引。 */
    private LambdaQueryWrapper<HarnessMemoryDO> byScope(String scopeId) {
        return new LambdaQueryWrapper<HarnessMemoryDO>().eq(HarnessMemoryDO::getScopeHash, hash(scopeId));
    }

    private static String hash(String scopeId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest((scopeId == null ? "" : scopeId).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，走不到这里；真走到了说明 JRE 被裁剪过，属于部署事故
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
