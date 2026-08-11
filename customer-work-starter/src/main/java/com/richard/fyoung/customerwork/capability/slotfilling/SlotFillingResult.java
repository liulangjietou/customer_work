package com.richard.fyoung.customerwork.capability.slotfilling;

import lombok.Getter;

import java.util.Map;

/**
 * 一轮槽位收集的结果：要么收齐（complete + values），要么还缺并给出追问语（nextPrompt）。
 * @author owlzhangfq@gmail.com
 */
@Getter
public class SlotFillingResult {

    private final String formName;
    private final boolean complete;
    /** 未完成时对用户的追问语；完成时为 null。 */
    private final String nextPrompt;
    /** 已收集的槽位值（只读快照）。 */
    private final Map<String, String> values;

    public SlotFillingResult(String formName, boolean complete, String nextPrompt, Map<String, String> values) {
        this.formName = formName;
        this.complete = complete;
        this.nextPrompt = nextPrompt;
        this.values = values;
    }
}
