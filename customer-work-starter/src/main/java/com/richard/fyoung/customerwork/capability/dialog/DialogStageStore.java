package com.richard.fyoung.customerwork.capability.dialog;

import java.util.Optional;

/**
 * 对话阶段存储 SPI（持久化扩展点）。
 *
 * <p>默认由 {@link InMemoryDialogStageStore} 提供进程内实现；生产多实例部署下，进程内存储会导致
 * 请求被负载均衡到不同实例时阶段状态"归零"回 {@link DialogStage#GREETING}——可替换为
 * {@link MybatisDialogStageStore} 或自定义 Bean（如 Redis）跨实例共享同一份阶段状态。</p>
 *
 * <p>是否默认为 {@link DialogStage#GREETING} 的判定逻辑由 {@link DialogStageService} 统一负责，
 * 本 SPI 只做纯粹的存取，不内置默认值语义。</p>
 * @author owlzhangfq@gmail.com
 */
public interface DialogStageStore {

    /** 查找会话当前阶段；不存在返回 empty（由调用方决定默认值）。 */
    Optional<DialogStage> find(String sessionId);

    /** 设置会话当前阶段。 */
    void set(String sessionId, DialogStage stage);

    /** 移除会话阶段状态（会话结束时调用）。 */
    void remove(String sessionId);
}
