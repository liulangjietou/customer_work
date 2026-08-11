package com.richard.fyoung.customerwork.capability.slotfilling;

import lombok.Getter;
import lombok.Setter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单表单的收集进度（槽位值 + 正在追问的槽位名）。
 *
 * <p>从 {@link SlotFillingService} 内部类抽取为公共类，使 {@link SlotFillingStore} SPI 能序列化 / 持久化。</p>
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
public class SlotFillingProgress {

    private final Map<String, String> collected = new LinkedHashMap<>();

    /** 上一轮正在追问的槽位名（自由文本槽位）。 */
    private volatile String asking;

    /** 快照（不可变视图）。 */
    public Map<String, String> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(collected));
    }
}
