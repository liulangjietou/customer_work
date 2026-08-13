package com.richard.fyoung.customerwork.capability.semanticcache;

import com.richard.fyoung.customerwork.capability.semanticcache.entity.SemanticCacheDO;
import com.richard.fyoung.customerwork.capability.semanticcache.mapper.SemanticCacheMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis-Plus 语义缓存存储（生产实现：{@code semantic-cache.store-mode=jdbc} 时装配）。
 *
 * <p>多副本共享同一份缓存——进程内实现下命中率会被实例数直接除掉，而命中率正是这个功能的全部意义。</p>
 *
 * <p><b>全部操作失败只记日志、不抛异常</b>：与其他 Store 相反。缓存是纯粹的加速手段，
 * 它挂了最坏的结果是"这次没省下那一次模型调用"，绝不该让用户问不了问题。
 * 上层 {@link SemanticCacheService} 也做了同样的兜底，这里是第二层。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisSemanticCacheStore implements SemanticCacheStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisSemanticCacheStore.class);

    private final SemanticCacheMapper mapper;

    public MybatisSemanticCacheStore(SemanticCacheMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(SemanticCacheEntry entry) {
        try {
            SemanticCacheDO row = new SemanticCacheDO();
            row.setScopeId(entry.scopeId());
            row.setIntent(entry.intent());
            row.setQuestion(entry.question());
            row.setQuestionVector(entry.questionVector());
            row.setAnswer(entry.answer());
            row.setHitCount(entry.hitCount());
            row.setCreatedAtMs(entry.createdAtMs());
            row.setLastHitAtMs(entry.lastHitAtMs());
            mapper.insert(row);
        } catch (Exception e) {
            log.error("[MybatisSemanticCacheStore] save failed, errorCode={}, scopeId={}",
                "SEMCACHE-STORE-SAVE-FAIL", entry.scopeId(), e);
        }
    }

    @Override
    public List<SemanticCacheEntry> findCandidates(String scopeId, String intent, long notBeforeMs, int limit) {
        try {
            List<SemanticCacheDO> rows = mapper.selectCandidates(scopeId, intent, notBeforeMs, limit);
            List<SemanticCacheEntry> result = new ArrayList<>(rows.size());
            for (SemanticCacheDO row : rows) {
                result.add(toDomain(row));
            }
            return result;
        } catch (Exception e) {
            log.error("[MybatisSemanticCacheStore] findCandidates failed, errorCode={}, scopeId={}",
                "SEMCACHE-STORE-FIND-FAIL", scopeId, e);
            return List.of();
        }
    }

    @Override
    public void recordHit(Long id, long hitAtMs) {
        try {
            mapper.recordHit(id, hitAtMs);
        } catch (Exception e) {
            log.error("[MybatisSemanticCacheStore] recordHit failed, errorCode={}, id={}",
                "SEMCACHE-STORE-HIT-FAIL", id, e);
        }
    }

    @Override
    public long count(String scopeId) {
        try {
            return mapper.countByScope(scopeId);
        } catch (Exception e) {
            log.error("[MybatisSemanticCacheStore] count failed, errorCode={}, scopeId={}",
                "SEMCACHE-STORE-COUNT-FAIL", scopeId, e);
            return 0L;
        }
    }

    @Override
    public int evictLeastRecentlyUsed(String scopeId, int keepSize) {
        try {
            Long threshold = mapper.selectEvictThreshold(scopeId, keepSize);
            if (threshold == null) {
                return 0;
            }
            return mapper.deleteOlderThan(scopeId, threshold);
        } catch (Exception e) {
            log.error("[MybatisSemanticCacheStore] evict failed, errorCode={}, scopeId={}",
                "SEMCACHE-STORE-EVICT-FAIL", scopeId, e);
            return 0;
        }
    }

    @Override
    public int clear(String scopeId) {
        try {
            return mapper.deleteByScope(scopeId);
        } catch (Exception e) {
            log.error("[MybatisSemanticCacheStore] clear failed, errorCode={}, scopeId={}",
                "SEMCACHE-STORE-CLEAR-FAIL", scopeId, e);
            return 0;
        }
    }

    @Override
    public List<SemanticCacheEntry> listByHits(String scopeId, int limit) {
        try {
            List<SemanticCacheDO> rows = mapper.selectByHits(scopeId, limit);
            List<SemanticCacheEntry> result = new ArrayList<>(rows.size());
            for (SemanticCacheDO row : rows) {
                result.add(toDomain(row));
            }
            return result;
        } catch (Exception e) {
            log.error("[MybatisSemanticCacheStore] listByHits failed, errorCode={}, scopeId={}",
                "SEMCACHE-STORE-LIST-FAIL", scopeId, e);
            return List.of();
        }
    }

    @Override
    public boolean remove(Long id) {
        try {
            return mapper.deleteById(id) > 0;
        } catch (Exception e) {
            log.error("[MybatisSemanticCacheStore] remove failed, errorCode={}, id={}",
                "SEMCACHE-STORE-REMOVE-FAIL", id, e);
            return false;
        }
    }

    private SemanticCacheEntry toDomain(SemanticCacheDO row) {
        return new SemanticCacheEntry(
            row.getId(),
            row.getScopeId(),
            row.getIntent(),
            row.getQuestion(),
            row.getQuestionVector(),
            row.getAnswer(),
            row.getHitCount() == null ? 0L : row.getHitCount(),
            row.getCreatedAtMs() == null ? 0L : row.getCreatedAtMs(),
            row.getLastHitAtMs() == null ? 0L : row.getLastHitAtMs());
    }
}
