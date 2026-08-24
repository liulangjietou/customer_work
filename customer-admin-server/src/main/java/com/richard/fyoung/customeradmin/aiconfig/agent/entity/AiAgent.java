package com.richard.fyoung.customeradmin.aiconfig.agent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能体配置。{@code capabilities} 落库为逗号分隔字符串（如 {@code "chat,vibecoding"}），
 * Service 层与 {@link com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentVO} 之间转换为 List。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_agent")
public class AiAgent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 多租户行级隔离列；由租户拦截器写入，跨租户运维查询时用于恢复可信运行上下文。 */
    private String tenantId;

    private String agentName;
    /** 用于动态菜单路由，[a-z0-9-]+。 */
    private String agentCode;
    private Long modelId;
    /** 可选的不可变模型路由策略绑定；为空时沿用主模型/备用模型链。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long modelRoutePolicyId;
    private String systemPrompt;
    private String capabilities;
    private String icon;
    /** 0停用 / 1启用。 */
    private Integer status;

    /**
     * Admin 多 Pod 运行时实例修订号。任何影响 Agent 装配的写操作原子递增，缓存命中前按该值校验。
     */
    private Long runtimeRevision;

    // ---- 高级参数（全部选填，null=用框架/工厂默认；updateStrategy=ALWAYS 保证编辑时能清空回默认） ----

    /** ReAct 最大迭代轮数（null=默认10）。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer maxIters;
    /** 工具执行超时秒数（null=框架默认5分钟）。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer toolTimeoutSeconds;
    /** 工具执行最大尝试次数（null=框架默认1次）。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer toolMaxAttempts;
    /** 上下文压缩触发消息数（null=不启用压缩）。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer compressTriggerMsgs;
    /** 压缩后保留最近消息数（null=默认10）。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer compressKeepMsgs;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}
