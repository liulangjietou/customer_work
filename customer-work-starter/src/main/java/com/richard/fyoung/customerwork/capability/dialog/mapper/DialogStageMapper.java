package com.richard.fyoung.customerwork.capability.dialog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.capability.dialog.entity.DialogStageDO;

/**
 * 对话阶段 Mapper（由 {@code @MapperScan} 扫描绑定，不加 {@code @Mapper}）。
 *
 * <p>基础 CRUD 走 {@link BaseMapper}；仅 {@link #upsert} 因需要 {@code ON DUPLICATE KEY UPDATE}
 * 语义写在 XML 中。</p>
 * @author owlzhangfq@gmail.com
 */
public interface DialogStageMapper extends BaseMapper<DialogStageDO> {

    /** 新建或按 session_id 覆盖 stage（对应旧 UPSERT_SQL）。 */
    int upsert(DialogStageDO record);
}
