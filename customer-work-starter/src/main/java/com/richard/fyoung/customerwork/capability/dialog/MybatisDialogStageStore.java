package com.richard.fyoung.customerwork.capability.dialog;

import com.richard.fyoung.customerwork.capability.dialog.entity.DialogStageDO;
import com.richard.fyoung.customerwork.capability.dialog.mapper.DialogStageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * MyBatis-Plus 对话阶段存储（生产实现：补齐 {@code dialog.store-mode=jdbc} 的跨实例共享落地）。
 *
 * <p>把会话当前阶段写入 {@code cw_dialog_stage} 表，多实例部署下请求被负载均衡到不同实例时
 * 仍能读到同一份阶段状态——解决进程内存储"阶段归零回 GREETING"的问题。</p>
 *
 * <p>由 {@link DialogStageConfig} 按 {@code dialog.store-mode=jdbc} 装配；建表由
 * Flyway 统一负责。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisDialogStageStore implements DialogStageStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisDialogStageStore.class);

    private final DialogStageMapper mapper;

    public MybatisDialogStageStore(DialogStageMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<DialogStage> find(String sessionId) {
        try {
            DialogStageDO entity = mapper.selectById(sessionId);
            return entity == null ? Optional.empty() : Optional.of(DialogStage.valueOf(entity.getStage()));
        } catch (Exception e) {
            log.error("[MybatisDialogStageStore] find failed, errorCode={}, sessionId={}",
                "DIALOGSTAGE-STORE-FIND-FAIL", sessionId, e);
            return Optional.empty();
        }
    }

    @Override
    public void set(String sessionId, DialogStage stage) {
        try {
            DialogStageDO entity = new DialogStageDO();
            entity.setSessionId(sessionId);
            entity.setStage(stage.name());
            mapper.upsert(entity);
        } catch (Exception e) {
            log.error("[MybatisDialogStageStore] set failed, errorCode={}, sessionId={}",
                "DIALOGSTAGE-STORE-SET-FAIL", sessionId, e);
        }
    }

    @Override
    public void remove(String sessionId) {
        try {
            mapper.deleteById(sessionId);
        } catch (Exception e) {
            log.error("[MybatisDialogStageStore] remove failed, errorCode={}, sessionId={}",
                "DIALOGSTAGE-STORE-REMOVE-FAIL", sessionId, e);
        }
    }
}
