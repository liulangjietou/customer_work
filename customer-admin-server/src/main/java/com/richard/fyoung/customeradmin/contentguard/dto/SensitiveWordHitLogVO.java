package com.richard.fyoung.customeradmin.contentguard.dto;

import lombok.Data;

import java.util.List;

/**
 * 敏感词命中记录展示对象。
 * @author owlzhangfq@gmail.com
 */
@Data
public class SensitiveWordHitLogVO {

    private Long id;

    /** 命中方向：INBOUND 用户输入 / OUTBOUND 模型输出。 */
    private String direction;

    /** 整体决策：BLOCK/MASK/REVIEW。 */
    private String action;

    /** 命中词（已拆成列表，便于前端逐个渲染标签）。 */
    private List<String> words;

    /** 命中类目。 */
    private List<String> categories;

    /** 命中词个数。 */
    private Integer hitCount;

    private String agentName;
    private String sessionId;
    private String userId;

    /** 原文片段（已按 starter 侧配置截断）。 */
    private String snippet;

    private Long createdAtMs;
}
