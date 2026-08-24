package com.richard.fyoung.customeradmin.workspace.memory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能体跨会话长期记忆 DO（workspace/MEMORY.md 的权威存储）。
 * 时间列由数据库 DEFAULT CURRENT_TIMESTAMP / ON UPDATE 维护，代码侧不做审计填充
 * （写入方是运行时同步链路，不在登录上下文里，套 createBy/updateBy 填充器反而会拿到空值）。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_agent_memory")
public class AiAgentMemory {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 智能体编码（ai_agent.agent_code），唯一键。 */
    private String agentCode;

    /** 长期记忆内容（MEMORY.md 全文）。 */
    private String content;

    /** 乐观锁版本；每次成功回写递增。 */
    private Long version;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
