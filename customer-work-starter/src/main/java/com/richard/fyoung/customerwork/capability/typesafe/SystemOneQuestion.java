package com.richard.fyoung.customerwork.capability.typesafe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TypeSafe System One 的三种问题类型，字段与官方 HTTP API 一一对应。
 *
 * <p>见 https://docs.typesafe.ai/primitives ：Choice 从选项中选一个、Score 按有序档位打分、
 * Noul 回答是/否的概率。一次请求可以批量携带多个问题（官方 Speculative Fan-out 模式），
 * 边际成本几乎为零，所以同一轮对话的多个决策点应当合并成一次调用。</p>
 */
public sealed interface SystemOneQuestion
    permits SystemOneQuestion.Choice, SystemOneQuestion.Score, SystemOneQuestion.Noul {

    /** 序列化成 API 请求体里单个问题的 JSON 结构。 */
    Map<String, Object> toPayload();

    /**
     * 单选：{@code criteria} 为「选项编码 → 描述」，官方上限 255 个选项。
     *
     * @param instructions 问题说明
     * @param criteria     选项编码 → 选项描述；遍历顺序即展示顺序
     */
    record Choice(String instructions, Map<String, String> criteria) implements SystemOneQuestion {
        @Override
        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "choice");
            payload.put("instructions", instructions);
            payload.put("criteria", criteria);
            return payload;
        }
    }

    /**
     * 有序档位打分：{@code levels} 按从低到高排列，下标即档位编号（0 起），官方要求 2~10 档。
     *
     * @param instructions 问题说明
     * @param levels       各档描述，下标 0 为最低档
     */
    record Score(String instructions, List<String> levels) implements SystemOneQuestion {
        @Override
        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "score");
            payload.put("instructions", instructions);
            payload.put("criteria", levels);
            return payload;
        }
    }

    /**
     * 是/否概率。{@code whenTrue}/{@code whenFalse} 可选，用于说明什么算「是」、什么算「否」。
     *
     * @param instructions 问题说明
     * @param whenTrue     「是」的判定口径
     * @param whenFalse    「否」的判定口径
     */
    record Noul(String instructions, String whenTrue, String whenFalse) implements SystemOneQuestion {
        @Override
        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "noul");
            payload.put("instructions", instructions);
            payload.put("criteria", Map.of("true", whenTrue, "false", whenFalse));
            return payload;
        }
    }
}
