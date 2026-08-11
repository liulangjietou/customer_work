package com.richard.fyoung.customeradmin.contentguard.runtime;

import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardGatewayProvider;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordAction;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordHitDirection;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordHitLogStore;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordHitRecord;
import com.richard.fyoung.customerwork.safety.sensitiveword.entity.SensitiveWordHitLogEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * admin 侧命中日志落库：写到与 8080 客服链路同一张 {@code cw_sensitive_word_hit_log}。
 *
 * <p>混在同一张表是有意的——看板要回答的是"整个系统被触发了什么"，按来源分表只会让运营在两个页面之间
 * 来回对照。要区分来源看 {@code agent_name} 即可。</p>
 *
 * <p>写失败只记 error 不抛：调用方是异步 Sink 的后台线程，抛出去没人接，而命中日志写不进去
 * 不该有任何主链路后果。</p>
 * @author owlzhangfq@gmail.com
 */
public class GatewaySensitiveWordHitLogStore implements SensitiveWordHitLogStore {

    private static final Logger log = LoggerFactory.getLogger(GatewaySensitiveWordHitLogStore.class);

    private static final String SEPARATOR = ",";

    private final ContentGuardGatewayProvider gatewayProvider;

    public GatewaySensitiveWordHitLogStore(ContentGuardGatewayProvider gatewayProvider) {
        this.gatewayProvider = gatewayProvider;
    }

    @Override
    public void save(SensitiveWordHitRecord record) {
        if (record == null) {
            return;
        }
        try {
            gatewayProvider.get().hitLogMapper().insert(toDO(record));
        } catch (Exception e) {
            log.error("[CONTENT-GUARD] admin hit log save failed, code={}, direction={}",
                "CONTENTGUARD-HITLOG-SAVE-FAIL", record.direction(), e);
        }
    }

    @Override
    public List<SensitiveWordHitRecord> findRecent(int limit) {
        // admin 侧的查询走 SensitiveWordHitLogService（带分页与聚合），此处不重复实现
        throw new UnsupportedOperationException("query hit logs via SensitiveWordHitLogService");
    }

    private SensitiveWordHitLogEntity toDO(SensitiveWordHitRecord record) {
        SensitiveWordHitLogEntity row = new SensitiveWordHitLogEntity();
        row.setDirection(record.direction() == null
            ? SensitiveWordHitDirection.INBOUND.name() : record.direction().name());
        row.setAction(record.action() == null ? SensitiveWordAction.REVIEW.name() : record.action().name());
        row.setWords(join(record.words()));
        row.setCategories(join(record.categories()));
        row.setHitCount(CollectionUtils.isEmpty(record.words()) ? 0 : record.words().size());
        row.setAgentName(record.agentName());
        row.setSessionId(record.sessionId());
        row.setUserId(record.userId());
        row.setSnippet(record.snippet());
        row.setCreatedAtMs(record.createdAtMs());
        return row;
    }

    private static String join(List<String> values) {
        return CollectionUtils.isEmpty(values) ? "" : String.join(SEPARATOR, values);
    }
}
