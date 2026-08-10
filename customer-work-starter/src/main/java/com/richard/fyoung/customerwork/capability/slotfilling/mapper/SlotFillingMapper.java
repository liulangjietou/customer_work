package com.richard.fyoung.customerwork.capability.slotfilling.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.capability.slotfilling.entity.SlotFillingProgressDO;

/**
 * 槽位收集进度 Mapper（由 {@code @MapperScan} 扫描绑定，不加 {@code @Mapper}）。
 *
 * <p>基础 CRUD 走 {@link BaseMapper}；仅 {@link #upsert} 因需要 {@code ON DUPLICATE KEY UPDATE}
 * 语义写在 XML 中。</p>
 * @author owlzhangfq@gmail.com
 */
public interface SlotFillingMapper extends BaseMapper<SlotFillingProgressDO> {

    /** 新建或按 progress_key 覆盖 asking/collected_json（对应旧 UPSERT_SQL）。 */
    int upsert(SlotFillingProgressDO record);
}
