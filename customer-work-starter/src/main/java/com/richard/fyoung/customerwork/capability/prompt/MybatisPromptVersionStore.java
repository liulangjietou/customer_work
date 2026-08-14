package com.richard.fyoung.customerwork.capability.prompt;

import com.richard.fyoung.customerwork.capability.prompt.entity.PromptVersionDO;
import com.richard.fyoung.customerwork.capability.prompt.mapper.PromptVersionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis-Plus 提示词版本存储（{@code prompt-version.store-mode=jdbc} 时装配）。
 *
 * <p>失败只记日志：版本留痕是旁路能力，它挂了顶多让归因少一条线索，不该阻断对话。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisPromptVersionStore implements PromptVersionStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisPromptVersionStore.class);

    private final PromptVersionMapper mapper;

    public MybatisPromptVersionStore(PromptVersionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void record(PromptVersion version) {
        if (version == null || version.fingerprint().isEmpty()) {
            return;
        }
        try {
            PromptVersionDO row = new PromptVersionDO();
            row.setFingerprint(version.fingerprint());
            row.setContent(version.content());
            row.setLength(version.length());
            row.setCapturedAtMs(version.capturedAtMs());
            mapper.insertIgnore(row);
        } catch (Exception e) {
            log.error("[MybatisPromptVersionStore] record failed, errorCode={}, fingerprint={}",
                "PROMPT-VERSION-SAVE-FAIL", version.fingerprint(), e);
        }
    }

    @Override
    public Optional<PromptVersion> find(String fingerprint) {
        try {
            PromptVersionDO row = mapper.selectById(fingerprint);
            return row == null ? Optional.empty() : Optional.of(toDomain(row));
        } catch (Exception e) {
            log.error("[MybatisPromptVersionStore] find failed, errorCode={}, fingerprint={}",
                "PROMPT-VERSION-FIND-FAIL", fingerprint, e);
            return Optional.empty();
        }
    }

    @Override
    public List<PromptVersion> findRecent(int limit) {
        try {
            List<PromptVersionDO> rows = mapper.selectRecent(limit);
            List<PromptVersion> result = new ArrayList<>(rows.size());
            for (PromptVersionDO row : rows) {
                result.add(toDomain(row));
            }
            return result;
        } catch (Exception e) {
            log.error("[MybatisPromptVersionStore] findRecent failed, errorCode={}",
                "PROMPT-VERSION-RECENT-FAIL", e);
            return List.of();
        }
    }

    private PromptVersion toDomain(PromptVersionDO row) {
        return new PromptVersion(
            row.getFingerprint(),
            row.getContent(),
            row.getLength() == null ? 0 : row.getLength(),
            row.getCapturedAtMs() == null ? 0L : row.getCapturedAtMs());
    }
}
