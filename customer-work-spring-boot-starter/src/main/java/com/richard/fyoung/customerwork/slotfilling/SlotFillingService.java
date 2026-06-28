package com.richard.fyoung.customerwork.slotfilling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多轮槽位收集服务（借鉴 AliGo「事项收集智能体」）：按 (sessionId, form) 维护收集进度，
 * 每轮抽取/补全后给出"还缺哪个→追问"或"齐了→可执行"。
 *
 * <p>抽取规则（确定性、可离线测）：</p>
 * <ol>
 *   <li>若上一轮在追问某<b>自由文本</b>槽位，则本轮整句作为其值；</li>
 *   <li>对所有<b>带正则</b>且未填的槽位，尝试从本轮文本抽取；</li>
 *   <li>取第一个 required 且未填的槽位追问；全填则完成并清理会话状态。</li>
 * </ol>
 * @author owlzhangfq@gmail.com
 */
@Service
public class SlotFillingService {

    private static final Logger log = LoggerFactory.getLogger(SlotFillingService.class);

    private final ConcurrentHashMap<String, Progress> sessions = new ConcurrentHashMap<>();

    /** 提交一轮用户输入，推进表单收集。 */
    public SlotFillingResult submit(String sessionId, SlotFillingForm form, String userText) {
        String key = sessionId + ":" + form.getName();
        Progress progress = sessions.computeIfAbsent(key, k -> new Progress());

        // 1) 上一轮在追问的自由文本槽位：整句作为其值
        if (progress.asking != null) {
            Slot asked = findSlot(form, progress.asking);
            if (asked != null && asked.getPattern() == null && StringUtils.hasText(userText)) {
                progress.collected.put(asked.getName(), userText.trim());
            }
        }
        // 2) 带正则的未填槽位：尝试抽取
        for (Slot slot : form.getSlots()) {
            if (!progress.collected.containsKey(slot.getName())) {
                String v = slot.extract(userText);
                if (v != null) {
                    progress.collected.put(slot.getName(), v);
                }
            }
        }
        // 3) 第一个 required 且未填 → 追问
        for (Slot slot : form.getSlots()) {
            if (slot.isRequired() && !progress.collected.containsKey(slot.getName())) {
                progress.asking = slot.getName();
                log.info("slot-filling: form={}, session={}, asking={}", form.getName(), sessionId, slot.getName());
                return new SlotFillingResult(form.getName(), false, slot.getAskPrompt(), snapshot(progress));
            }
        }
        // 完成：清理状态
        Map<String, String> values = snapshot(progress);
        sessions.remove(key);
        log.info("slot-filling completed: form={}, session={}, values={}", form.getName(), sessionId, values.keySet());
        return new SlotFillingResult(form.getName(), true, null, values);
    }

    /** 放弃当前会话的某表单收集（用户中途取消）。 */
    public void reset(String sessionId, String formName) {
        sessions.remove(sessionId + ":" + formName);
    }

    private Slot findSlot(SlotFillingForm form, String name) {
        return form.getSlots().stream().filter(s -> s.getName().equals(name)).findFirst().orElse(null);
    }

    private Map<String, String> snapshot(Progress progress) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(progress.collected));
    }

    /** 单表单的收集进度。 */
    private static final class Progress {
        private final Map<String, String> collected = new LinkedHashMap<>();
        /** 上一轮正在追问的槽位名。 */
        private volatile String asking;
    }
}
