package com.richard.fyoung.customerwork.capability.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.capability.badcase.BadcaseService;
import com.richard.fyoung.customerwork.capability.badcase.BadcaseSource;
import com.richard.fyoung.customerwork.core.constant.FactTypes;
import com.richard.fyoung.customerwork.core.memory.FactLog;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 质检失败反馈记录器（数据飞轮：把质检不通过的会话沉淀为可追溯的事实流水）。
 *
 * <p>{@link QualityInspectionService} 只负责纯粹的规则打分，不产生任何持久化副作用；本类在其上包一层——
 * 质检不通过时把回复内容 + 扣分项写入 {@link FactLog}（三层记忆体系的 L3：只追加、可审计），
 * 供离线复盘 / 后续人工筛选后回流知识库或补充评测集用例。</p>
 *
 * <p><b>两条去向</b>：失败会话既写入 {@link FactLog}（L3 审计流水，只追加、永不改写），
 * 也登记进 {@link BadcaseService} 的待筛队列（有状态的运营工作流）。与
 * {@code FeedbackService} 同一手法与同一理由。</p>
 *
 * <p><b>诚实边界</b>：不做自动回流。质检失败是规则命中，可能只是话术不合规范而用户其实满意，
 * 直接拿去改知识库既没必要也不安全；筛选那一步由人做。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class QualityFeedbackRecorder {

    private static final Logger log = LoggerFactory.getLogger(QualityFeedbackRecorder.class);

    private final QualityInspectionService qualityService;
    private final FactLog factLog;
    private final TenantResolver tenantResolver;
    private final CustomerWorkProperties properties;
    private final ObjectProvider<BadcaseService> badcaseServiceProvider;
    private final ObjectMapper mapper = new ObjectMapper();

    public QualityFeedbackRecorder(QualityInspectionService qualityService, FactLog factLog,
                                   TenantResolver tenantResolver, CustomerWorkProperties properties,
                                   ObjectProvider<BadcaseService> badcaseServiceProvider) {
        this.qualityService = qualityService;
        this.factLog = factLog;
        this.tenantResolver = tenantResolver;
        this.properties = properties;
        this.badcaseServiceProvider = badcaseServiceProvider;
    }

    /** 质检并在不通过时记录反馈事实；返回值与 {@link QualityInspectionService#inspect} 一致。 */
    public QualityReport inspectAndRecord(String sessionId, List<String> replies) {
        QualityReport report = qualityService.inspect(replies);
        if (!report.isPassed()) {
            recordFailure(sessionId, replies, report);
            collectBadcase(sessionId, report);
        }
        return report;
    }

    private void recordFailure(String sessionId, List<String> replies, QualityReport report) {
        try {
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("type", FactTypes.QUALITY_FAILURE);
            fact.put("sessionId", sessionId);
            fact.put("score", report.getScore());
            fact.put("issues", report.getIssues());
            fact.put("replies", replies);
            factLog.append(tenantResolver.resolve(sessionId), mapper.writeValueAsString(fact));
        } catch (Exception e) {
            log.error("record quality failure fact failed, errorCode={}, sessionId={}",
                "QUALITY-FEEDBACK-RECORD-FAIL", sessionId, e);
        }
    }

    /**
     * 登记进 badcase 待筛队列。
     *
     * <p>质检针对的是一批回复而非某条消息，故不传 messageId——{@code BadcaseService} 会退化为
     * 取会话里最后一条机器人回复来还原现场。明细带上得分与扣分项，运营据此判断是真问题还是误报。</p>
     */
    private void collectBadcase(String sessionId, QualityReport report) {
        if (!properties.getBadcase().isAutoCollect()) {
            return;
        }
        BadcaseService badcaseService = badcaseServiceProvider.getIfAvailable();
        if (badcaseService == null) {
            return;
        }
        String detail = "score=" + report.getScore() + ", issues=" + report.getIssues();
        badcaseService.record(BadcaseSource.QUALITY_FAILURE, sessionId, null, detail);
    }
}
