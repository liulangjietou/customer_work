# 工作区实时终态契约

本变更属于消息可靠性批次中的终态部分，基于 `cf859bdd`。受理回执、持久化重试对账、H5 与坐席重连仍在后续实施台账中，不能把本变更标为整个第二批完成。

## 调用链与职责

- 普通对话：ChatPanel → chatConversations → streamSse → ChatController → ChatService → ReAct/Harness → AgentStateStore。
- 编码：VibeCodingPanel → vibeConversations → VibeCodingController → VibeCodingService → ChatService；编码服务还负责文件变更检测、对象存储保存和审计提交。
- 协作：CollaborativeCodingService 依次调用分析、编码、审查。内部编码终态只驱动编排；外层在全部角色结束后发送一次终态。诊断与重构在 AiCodingTaskService 收尾，拒绝或超时不调用修改流。

Controller 保留协议映射职责，前端只展示状态。主 Agent 回复的完成依据由 ChatCompletionVerifier 在会话锁内直接回读权威状态存储，匹配可信主体、会话、本轮用户消息、回复 ID、正文和结束原因，不使用 Agent 的内存状态或历史缓存自证持久化。

## SSE 事件

原有 `message`、`node:*`、`file_change`、`test_report`、`plan`、`plan_result`、`role_stage` 保持兼容。新增独立 `terminal` JSON 事件，旧 `done: [DONE]` 只表示传输结束。

```json
{
  "turnId": "当前用户消息的框架 ID",
  "messageId": "主 Agent 回复的框架 ID",
  "phase": "FINAL",
  "finishReason": "stop",
  "historySaved": true,
  "artifactsSaved": true,
  "error": null
}
```

- `phase` 使用已有的 FINAL、STOPPED、WAITING、FAILED、UNKNOWN；USER_INPUT 与 PROCESS 不能作为最终状态。
- `historySaved` 只证明主 Agent 本轮输入和回复已保存并回读。它不证明工具业务副作用、长期记忆、协作角色输出或审计已落库。
- `artifactsSaved` 只用于编码产物；未涉及为 null，保存成功为 true，存储未配置、归档超限、文件读取失败或上传失败为 false。产物未确认时不能显示完整成功。
- 角色流程全部完成可以返回 FINAL；协作只包含一次性分析角色时没有主 Agent 会话记录，historySaved 为 false。协作角色历史及完整终态的重连对账随受理记录继续接通，当前不能把编码历史气泡等同于整个协作流程的历史结果。
- 底层运行异常使用可理解的失败提示；状态无法确认时保留已收到的内容，不根据点击过“终止”或 EOF 推断成功或已停止。

## 结束原因的来源

AgentScope 2.0.3 的普通模型回复可能不写 `GenerateReason`；缺少该字段时 getter 默认返回 MODEL_STOP，旧记录不能因此被认定完成。

ModelCompletionMiddleware 在原始模型响应边界记录实际 `finishReason`，不改框架的 GenerateReason，也不控制推理循环。它经 starter 的治理装配和 Admin 的现有运行时装配进入调用。模型名、结构化输出能力、上下文窗口和生成参数均保持委托。

展示优先使用框架明确记录的中断、审批等原因；否则采用新记录的模型协议原因：stop → FINAL、tool_calls → PROCESS、length → STOPPED、content_filter → FAILED。未认识或缺少原因保持 UNKNOWN。旧消息不补造元数据。

## 保存与取消

编码服务在请求线程冻结会话目录及对象键，异步保存不重新读取租户 ThreadLocal。先检测最终文件变更、保存产物并提交审计，再发布终态。归档读不到文件时不会用不完整归档覆盖已有权威副本；失败保留本地文件。取消仍保存已生成产物，但不向已经断开的客户端伪造终态。

审计仍沿用既有异步记录器；本契约没有声称审计提交等于审计落库。普通 SSE 也没有新增关闭页面后持续执行、宕机恢复或事件重放平台。

## 前端行为

两种工作区复用同一收尾规则。完成状态未知时展示琥珀色提示，保留文本和下一条草稿；明确业务拒绝展示失败；收到终态后的连接错误不覆盖该终态。等待当前传输关闭后才开放下一轮，避免旧连接收尾覆盖新任务状态。

只有 STOPPED 才提供继续入口，包括重新打开已记录为 STOPPED 的历史。未知、失败、等待确认和正常完成都不会因曾经点击终止而出现继续入口。继续由用户显式触发，既有工具授权与审批仍生效。

## 回归证据与剩余门禁

已先复现：两种工作区的 EOF/停止竞态、历史停止状态丢失、后端兜底缺少失败终态、编码保存晚于终态、失败编码被记成功、协作提前完成、编码失败仍进入审查、重构拒绝没有终态。

定向验证包括真实 ReAct 与 Harness 配合确定性模型的正常保存/保存失败、AgentState JSON 往返、主体及轮次核对、模型能力透传、对象存储失败/超限/租户切换、取消只保存一次、诊断与审批边界。浏览器覆盖两种工作区的四种终态及移动布局，数据来自明确接口夹具，不作为真实模型或业务工具联调证明。

完整 Maven、Admin Vitest/Playwright、H5 Vitest、两端类型与构建及截图核对均已通过，具体结果与验证边界见 [终态验证记录](chat-terminal-validation.md)。
