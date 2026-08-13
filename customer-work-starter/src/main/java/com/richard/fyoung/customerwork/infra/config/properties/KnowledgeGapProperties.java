package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

/**
 * 知识盲区分析配置。
 *
 * <p>盲区排行要攒够一段时间才有说服力，memory 模式重启清零等于永远看不出"反复"，生产切 jdbc。</p>
 */
@Data
public class KnowledgeGapProperties {

    /** 是否记录检索未命中（默认开）。它只在未命中时做一次 upsert，开销可忽略。 */
    private boolean enabled = true;

    /** 存储模式：memory（进程内，默认）| jdbc（落 cw_knowledge_gap）。 */
    private String storeMode = "memory";

    /** 最短问题长度：太短的（"嗯""在吗"）本就不该指望知识库命中，计进去只会淹没真正的盲区。 */
    private int minQuestionLength = 4;
}
