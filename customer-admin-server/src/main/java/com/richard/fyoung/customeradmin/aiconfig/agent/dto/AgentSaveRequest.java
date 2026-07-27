package com.richard.fyoung.customeradmin.aiconfig.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 智能体新建/编辑请求。{@code mcpIds}/{@code skillIds}/{@code systemToolIds}/{@code subAgentIds}/
 * {@code knowledgeBaseIds} 可选多选；{@code modelId}（主模型）必填；{@code backupModelIds} 为有序备用模型列表
 * （可空=无备模型），运行时主模型失败按序切换。
 * 5 个高级参数全部选填，null 表示使用框架/工厂默认值（取值范围校验见 AgentService#validate）。
 *
 * <p>{@code knowledgeBaseIds} 追加在末尾而非与其它 {@code xxxIds} 并列：记录组件顺序即位置构造器签名，
 * 插在中间会让所有既有位置构造调用点静默错位（类型恰好相同的相邻参数不会编译报错），追加则安全。</p>
 * @author owlzhangfq@gmail.com
 */
public record AgentSaveRequest(
    @NotBlank(message = "agentName 不能为空") String agentName,
    @NotBlank(message = "agentCode 不能为空") String agentCode,
    @NotNull(message = "modelId 不能为空") Long modelId,
    List<Long> backupModelIds,
    List<Long> mcpIds,
    List<Long> skillIds,
    List<Long> systemToolIds,
    String systemPrompt,
    List<String> capabilities,
    String icon,
    Integer status,
    List<Long> subAgentIds,
    Integer maxIters,
    Integer toolTimeoutSeconds,
    Integer toolMaxAttempts,
    Integer compressTriggerMsgs,
    Integer compressKeepMsgs,
    /** 绑定的 RAG 知识库 ID 列表（可空=不做知识库检索），只允许绑定启用且连通性测试成功的知识库。 */
    List<Long> knowledgeBaseIds) {
}
