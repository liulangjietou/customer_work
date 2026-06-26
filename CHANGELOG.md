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
  - 详见 [docs/customer-web操作文档.md](docs/customer-web操作文档.md)。
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
