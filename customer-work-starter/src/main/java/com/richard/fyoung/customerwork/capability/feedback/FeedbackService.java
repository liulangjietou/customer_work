package com.richard.fyoung.customerwork.capability.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.core.memory.FactLog;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户反馈服务（消息级点赞/点踩闭环，数据飞轮的另一条输入通道）。
 *
 * <p>与 {@link com.richard.fyoung.customerwork.capability.quality.QualityFeedbackRecorder}
 * （系统主动质检、命中规则判不通过）互补——本类是<b>用户主动表达</b>的负反馈信号，同样沉淀到
 * {@link FactLog}（三层记忆体系 L3：只追加、可审计），供离线复盘 / 后续人工筛选后回流知识库或
 * 补充评测集用例。</p>
 *
 * <p><b>诚实边界</b>：本类只做"记录"这一步——把负反馈持久化到可查询的事实流水里；
 * "从事实流水筛选、回流知识库/评测集"是离线人工或独立批处理任务的职责，不在本类自动完成
 * （与 {@code QualityFeedbackRecorder} 同一边界声明）。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    private static final String FACT_TYPE = "negative-feedback";

    private final FeedbackStore store;
    private final FactLog factLog;
    private final TenantResolver tenantResolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public FeedbackService(FeedbackStore store, FactLog factLog, TenantResolver tenantResolver) {
        this.store = store;
        this.factLog = factLog;
        this.tenantResolver = tenantResolver;
    }

    /** 提交一条消息级反馈；同一 messageId 重复提交按最新一次覆盖。DOWN 类型额外沉淀事实供飞轮复盘。 */
    public MessageFeedback submit(String sessionId, String messageId, FeedbackType type, String comment) {
        MessageFeedback feedback = new MessageFeedback(messageId, sessionId, type, comment,
            System.currentTimeMillis());
        store.save(feedback);
        log.info("feedback submitted: messageId={}, sessionId={}, type={}", messageId, sessionId, type);
        if (type == FeedbackType.DOWN) {
            recordNegativeFeedback(feedback);
        }
        return feedback;
    }

    public Optional<MessageFeedback> find(String messageId) {
        return store.find(messageId);
    }

    public List<MessageFeedback> findBySession(String sessionId) {
        return store.findBySession(sessionId);
    }

    private void recordNegativeFeedback(MessageFeedback feedback) {
        try {
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("type", FACT_TYPE);
            fact.put("sessionId", feedback.sessionId());
            fact.put("messageId", feedback.messageId());
            fact.put("comment", feedback.comment());
            factLog.append(tenantResolver.resolve(feedback.sessionId()), mapper.writeValueAsString(fact));
        } catch (Exception e) {
            log.error("record negative feedback fact failed, errorCode={}, messageId={}",
                "FEEDBACK-RECORD-FAIL", feedback.messageId(), e);
        }
    }
}
