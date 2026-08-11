package com.richard.fyoung.customerwork.capability.dialog;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内对话阶段存储（默认实现）。由 {@link DialogStageConfig} 按 {@code dialog.store-mode}
 * 装配（默认 memory）；下游声明自己的 {@link DialogStageStore} Bean 即可整体覆盖。
 * @author owlzhangfq@gmail.com
 */
public class InMemoryDialogStageStore implements DialogStageStore {

    private final ConcurrentHashMap<String, DialogStage> stages = new ConcurrentHashMap<>();

    @Override
    public Optional<DialogStage> find(String sessionId) {
        return Optional.ofNullable(stages.get(sessionId));
    }

    @Override
    public void set(String sessionId, DialogStage stage) {
        stages.put(sessionId, stage);
    }

    @Override
    public void remove(String sessionId) {
        stages.remove(sessionId);
    }
}
