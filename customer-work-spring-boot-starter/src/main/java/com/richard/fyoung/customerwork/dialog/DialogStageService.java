package com.richard.fyoung.customerwork.dialog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话阶段状态机服务：按会话维护当前 {@link DialogStage}，供 {@code DialogStageMiddleware} 动态组装提示词。
 *
 * <p>阶段流转由业务事件驱动（如信息收集中 → {@code COLLECTING}，转人工 → {@code ESCALATED}），
 * 也可沿主链路 {@link #advance(String)} 顺序推进。未知会话默认 {@code GREETING}。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class DialogStageService {

    private static final Logger log = LoggerFactory.getLogger(DialogStageService.class);

    private final ConcurrentHashMap<String, DialogStage> stages = new ConcurrentHashMap<>();

    /** 当前阶段；会话为空或未记录时为 {@link DialogStage#GREETING}。 */
    public DialogStage current(String sessionId) {
        if (sessionId == null) {
            return DialogStage.GREETING;
        }
        return stages.getOrDefault(sessionId, DialogStage.GREETING);
    }

    /** 显式设置阶段（业务事件驱动）。 */
    public void set(String sessionId, DialogStage stage) {
        if (sessionId == null || stage == null) {
            return;
        }
        stages.put(sessionId, stage);
        log.info("dialog stage set: session={}, stage={}", sessionId, stage);
    }

    /** 沿主链路推进一个阶段，返回新阶段。 */
    public DialogStage advance(String sessionId) {
        DialogStage next = current(sessionId).next();
        set(sessionId, next);
        return next;
    }

    /** 会话结束时清理阶段状态。 */
    public void reset(String sessionId) {
        if (sessionId != null) {
            stages.remove(sessionId);
        }
    }
}
