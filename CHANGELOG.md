# Changelog

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/) 与 [Keep a Changelog](https://keepachangelog.com/zh-CN/)。

## [Unreleased]

### Migration — AgentScope 2.0（`rc2.0` 分支）
- 全量迁移到 `io.agentscope:agentscope-harness:2.0.0-RC4`（经 `agentscope-bom` 管理），JDK 17。
- 会话持久化：`core.session.Session` → `core.state.AgentStateStore`（InMemory/JsonFile + extensions Redis/Mysql），
  Agent 无状态、按 `(userId, sessionId)` 由框架自动加载/持久化；删除手工 `saveTo/loadIfExists`。
- Agent 装配：`.memory()` → `.stateStore()`；调用入参全部带 `RuntimeContext`。
- 上下文压缩：`AutoContextMemory` → Harness `CompactionConfig`。
- 多 Agent：`core.pipeline.Pipelines` → Reactor 直接编排 / HarnessAgent Subagent。
- **多 Agent 真并行编排**：`MultiAgentOrchestrator` 的 `fanout` 修正为真并发——每个 `agent.call` 经
  `subscribeOn(Schedulers.boundedElastic())` 挪到独立线程（即便底层模型调用阻塞也能并行），叠加
  `flatMap` 并发限流（`multi-agent.max-concurrency`）、单专家 `timeout`（`multi-agent.timeout-seconds`）
  与 `onErrorResume` 错误隔离；新增确定性单测断言运行期峰值并发度（≥2 真并发 / =1 限流退化 / 错误隔离）。
- **多 Agent 智能路由 + reduce 归纳**：fanout 链路升级为 **路由 → 并行 → 归纳**——`routing-enabled` 用分诊器
  （`IntentResult`）只把问题发给相关专家（`expertsForIntent` 纯映射，other/失败广播全部）；`reduce-enabled` 用
  归纳器把多专家结论二次合成统一口径回复（单专家/关闭退化为拼接）。新增意图映射 / 退化路径单测。
- **并行编排可观测埋点**：`MultiAgentOrchestrator` 注入可选 `MeterRegistry`，暴露 `customerwork.mas.route{intent}`、
  `customerwork.mas.fanout.experts`、`customerwork.mas.expert{expert,outcome}`、`customerwork.mas.reduce{triggered}`
  到 `/actuator/prometheus`（无 Micrometer 时 no-op）；新增指标记录单测。
- **人工审批闭环（Human-in-the-Loop）**：新增 `approval` 包（`PendingApprovalService` + `ApprovalRequest` 充血状态机
  + `ApprovalType`/`ApprovalStatus`）与 `ApprovalController`（`GET /approvals`、`POST /approvals/{id}/approve|deny`）；
  退款工具 `submitRefund` 登记待审单（附审批单号），人工放行后经回调执行打款——**挂起 → 人工决策 → 生效** 闭环。
  与框架 Permission ASK（工具调用层闸门）互补；之所以落应用层而非绑定 `ReActAgent.CONFIRM_SINK_KEY`，因后者
  在 RC4 未暴露 Web 友好的公共回填 API。状态机终态不可变、重复决策 409、not-found 404。新增 7 个单测。
- **多 Agent 规则快车道（借鉴阿里商旅 AliGo 快慢车道）**：`MultiAgentOrchestrator` 路由前置规则层
  （`fast-route-enabled`）——关键词命中**唯一**意图直路由、跳过 LLM 分诊（省一次模型调用、提准降延迟）；
  命中多类/无命中再走 LLM 慢车道。`fastRouteIntent` 纯函数，新增 2 个确定性单测。
- **多轮槽位收集（借鉴 AliGo 事项收集智能体）**：新增 `slotfilling` 包（`Slot`/`SlotFillingForm`/
  `SlotFillingResult`/`SlotFillingService`）——按 (sessionId, form) 维护进度，带正则槽位任意句抽取、
  自由文本槽位追问轮取值；内置退款表单（订单号→原因）。`RefundFormController`（`POST /api/customer/forms/refund`）
  收齐后**串接 HITL** 生成待审退款单。新增 3 个确定性单测。
- **对话阶段状态机 / 动态 Prompt（借鉴 AliGo 状态机式 Prompt 组装）**：新增 `dialog` 包（`DialogStage` 枚举
  + `DialogStageService`）+ `DialogStageMiddleware`（第五段 `onSystemPrompt`）——按会话当前阶段
  （接待/收集/处理/确认/转人工）动态注入"聚焦当前主链路"指令，替代全量规则塞一个静态大 Prompt（降 token、提准）。
  中间件第 10 个，`@Component` 自动收集进 Agent。新增 4 个单测；starter 全套 164 tests 全绿。
- **自动化意图评测框架（借鉴 AliGo 测评系统）**：新增 `eval` 包（`EvalCase`/`EvalReport`/`IntentEvalRunner`）
  + classpath 评测集 `eval/intent-eval-cases.json`（用例沉淀复用）——量化规则快车道的准确率/覆盖率，
  离线确定性、可 CI 跑、可版本对比；`EvalReport.format()` 输出文本报告。新增 2 个单测（数据集加载 + 质量基线）。
  真实回复相关性评测需 LLM-as-judge（真实 Key），不在离线范围。
- **customer-web 端到端测试补强**：新增 `CustomerWebEndpointAvailabilityTest`（`@SpringBootTest` RANDOM_PORT +
  `TestRestTemplate`），离线确定性验证五套前端端点真实注册可达——actuator/health、OpenAPI、chat-completions、
  AG-UI、飞书 inbound 回调 + OpenAPI 装配。诚实标注覆盖边界：真实对话/事件序列/签名收发依赖真实 Key/公网/签名，
  企业微信 inbound 端点映射依赖 channel 启用（由 bean 装配测试覆盖）。customer-web 测试 1 → 7 个。
- **新增 2.0 能力**：Permission System（`PermissionConfig` 注入主 Agent）、Plan Mode、Compaction、
  Workspace/Sandbox、Subagent（统一经 `HarnessAgent.Builder.fromAgent(...)` 装配，见 `HarnessAgentFactory`）。
- **Middleware 五段**：8 个业务 Hook 全部迁移到 `MiddlewareBase`（ToolGuard/DynamicOptions/Masking/
  Observability/Audit/Latency/SelfCorrection/HumanApproval + 新增 TenantContext），覆盖
  `onAgent/onReasoning/onActing/onModelCall/onSystemPrompt`；`ObjectProvider<Hook>`→`ObjectProvider<MiddlewareBase>`。
- **Harness 深度**：分层记忆（`MemoryConfig` MEMORY.md + `environmentMemory`）、超大工具结果落盘
  （`ToolResultEviction`）、技能自进化（`SkillCurator`+管理工具）、Plan 文件目录、`additionalContextFile`、
  org 维度（`RuntimeContext.put`）。
- **安全沙箱执行**：`harness.sandbox.mode` = local（子进程）/ docker（容器，均 Harness 内置）；
  远端 k8s/e2b/daytona/agentrun 需 `agentscope-extensions-sandbox-*`。
- **配套前端 `customer-web` 模块**（Spring MVC，独立于 WebFlux 对话 API；同一个 `customerServiceAgent` Bean
  被四套官方能力接管）：
  - **admin**（`agentscope-admin-spring-boot-starter`）：管理控制台——会话/工具/权限/用量/子智能体端点 + Swagger UI，
    集成 AgentEvent 与 Permission HITL。
  - **chat-completions-web**（`agentscope-chat-completions-web-starter`）：OpenAI 兼容 `POST /v1/chat/completions`
    （同步 + SSE 流式）+ 内置聊天页 `/`。真机自测：自我介绍 / 订单查询(触发工具) / 流式 三轮真实对话通过。
  - **AG-UI**（`agentscope-agui-spring-boot-starter`）：`POST /agui/run` 标准富事件协议（SSE 类型化事件）。
    真机自测：事件流 `RUN_STARTED→TEXT_MESSAGE_*→RUN_FINISHED` + 真实回复通过。
  - **Studio**（`agentscope-extensions-studio`）：轨迹推送到外部 Studio 应用做可视化（默认关，连接失败优雅降级）。
  - **Channel·钉钉**（`agentscope-extensions-channel-dingtalk`）：`HarnessAgent.fromAgent(...).channel(DingTalkChannel)`
    Stream 模式接入钉钉机器人，群内 @ 即可对话（默认关，需 appKey/appSecret/robotCode；连接失败优雅降级）。
  - **Channel·飞书**（`agentscope-extensions-channel-feishu`）：inbound 应用+事件回调
    `/api/channels/feishu/{channelId}/callback`（url_verification 握手已实测：正确 token→200、错误→401）；
    outbound `FeishuWebhookNotifier` + `POST /push/feishu` 经自定义机器人 webhook 推送（关键词可配）。
  - **Channel·企业微信**（`agentscope-extensions-channel-wecom`）：inbound 应用+回调
    `/api/channels/wecom/{channelId}/callback`（GET URL 验证，实测端点映射+签名校验 401）。
  - 飞书/企业微信完整消息流转需自建应用回调地址指向公网可达地址；详见 [docs/customer-web操作文档.md](docs/customer-web操作文档.md)。
- **不可迁移**（框架移除）：TTS（`TTSHook`/`DashScopeRealtimeTTSModel`）、`PlanNotebook`、`Pipelines`、
  `SessionManager`/`StateModule` —— 详见 [docs/MIGRATION-2.0.md](docs/MIGRATION-2.0.md)。

### Changed
- 工程拆分为多模块：`customer-work-spring-boot-starter`（可复用，含 `@AutoConfiguration` 自动装配）
  + `customer-work-example`（可运行示例，包 `com.richard.fyoung.customerworkapp`）
  + `customer-work-downstream-sample`（下游接入示例，包 `com.acme.support`，含接入契约测试）
- starter 改用 HikariCP（替代 spring-jdbc），不再触发 DataSourceAutoConfiguration，下游零排除即插即用

### Added
- 多 Agent 编排（Pipeline fanout/sequential）、AG-UI 协议、TTS Hook
- 记忆/RAG 多后端：内存 / 百炼 / Mem0 / ReMe / 真实向量(SimpleKnowledge) / Dify
- Skill：classpath/filesystem 仓库、运行时加载、代码执行
- 模型层：多厂商（dashscope/openai/anthropic/gemini/ollama）+ 私有化兜底 FallbackChatModel
- 可观测：Micrometer 指标 + 原生 Tracing + Actuator + 优雅停机 + 定时维护
- 接入层安全：API Key 鉴权 + 限流；Nacos 配置中心（提示词热更新）
- Swagger / OpenAPI（springdoc-webflux）
- 业务工具后端接口化（`tool.backend.*`），使用者实现接口即可接自有系统
- 开源治理：LICENSE(Apache-2.0)、CONTRIBUTING、CODE_OF_CONDUCT、Issue/PR 模板、Dependabot、Docker Compose

### Security
- 移除仓库内硬编码密钥，全部改为环境变量注入

## [1.0.0]
- 基于 AgentScope Java 1.0.12 的生产级智能客服核心链路（会话/意图/工具/流式/持久化）
