package com.richard.fyoung.customerwork.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.memory.FactLog;
import com.richard.fyoung.customerwork.support.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p><b>诚实边界</b>：本类只做"记录"这一步——把失败会话持久化到可查询的事实流水里；
 * "从事实流水筛选、回流知识库/评测集"是离线人工或独立批处理任务的职责，不在本类自动完成。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class QualityFeedbackRecorder {

    private static final Logger log = LoggerFactory.getLogger(QualityFeedbackRecorder.class);

    private static final String FACT_TYPE = "quality-failure";

    private final QualityInspectionService qualityService;
    private final FactLog factLog;
    private final TenantResolver tenantResolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public QualityFeedbackRecorder(QualityInspectionService qualityService, FactLog factLog,
                                   TenantResolver tenantResolver) {
        this.qualityService = qualityService;
        this.factLog = factLog;
        this.tenantResolver = tenantResolver;
    }

    /** 质检并在不通过时记录反馈事实；返回值与 {@link QualityInspectionService#inspect} 一致。 */
    public QualityReport inspectAndRecord(String sessionId, List<String> replies) {
        QualityReport report = qualityService.inspect(replies);
        if (!report.isPassed()) {
            recordFailure(sessionId, replies, report);
        }
        return report;
    }

    private void recordFailure(String sessionId, List<String> replies, QualityReport report) {
        try {
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("type", FACT_TYPE);
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
}
