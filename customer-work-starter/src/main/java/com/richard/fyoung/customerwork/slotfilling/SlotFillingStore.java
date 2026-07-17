package com.richard.fyoung.customerwork.slotfilling;

import java.util.Optional;

/**
 * 槽位收集进度存储 SPI（持久化扩展点）。
 *
 * <p>默认由 {@link InMemorySlotFillingStore} 提供进程内实现；生产可替换为 JDBC / Redis 实现保证
 * 重启不丢失收集进度（退款表单的多轮信息收集中途重启可恢复）。</p>
 * @author owlzhangfq@gmail.com
 */
public interface SlotFillingStore {

    /** 保存（新建或覆盖）收集进度。 */
    void save(String key, SlotFillingProgress progress);

    /** 查找收集进度；不存在返回 empty。 */
    Optional<SlotFillingProgress> find(String key);

    /** 查找或创建收集进度（不存在时创建空进度）。 */
    SlotFillingProgress findOrCreate(String key);

    /** 删除收集进度（表单完成或用户取消时调用）。 */
    void delete(String key);
}
