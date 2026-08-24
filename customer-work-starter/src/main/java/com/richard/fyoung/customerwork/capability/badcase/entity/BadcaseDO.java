package com.richard.fyoung.customerwork.capability.badcase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * badcase 持久化对象（贫血数据袋）：与 {@code cw_badcase} 表一一映射。
 *
 * <p>领域实体见 {@link com.richard.fyoung.customerwork.capability.badcase.Badcase}（充血，带状态流转）。
 * 枚举以名字符串落库，转换在 Store 层完成。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_badcase")
public class BadcaseDO {

    /** badcase ID（应用赋值的 UUID，非自增）。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String source;
    private String sessionId;
    private String messageId;

    /** 用户问了什么（从聊天留痕回查）。 */
    private String userInput;

    /** AI 答了什么（从聊天留痕回查）。 */
    private String agentReply;

    /** 用户问题归一化哈希：线上复发观测键。 */
    private String signalHash;

    /** 原始信号明细：点踩存用户留言，质检存扣分项与得分。 */
    private String detail;

    private String status;

    /** 已回流成的知识条目 ID。 */
    private Long adoptedKnowledgeId;

    /** 已回流成的评测用例编号。 */
    private String adoptedEvalCaseId;

    private String handledBy;
    private Long handledAtMs;
    private String ignoreReason;
    private Long createdAtMs;
}
