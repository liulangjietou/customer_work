# AgentScope Java 1.x → 2.0 迁移说明（rc2.0 分支）

> 目标依赖：`io.agentscope:agentscope-harness:2.0.0-RC4`（经 `agentscope-bom` 统一管理）
> 基线分支：`main`（`io.agentscope:agentscope:1.0.12`）→ 迁移分支：`rc2.0`
> JDK 17 / Maven 3.9+ / Spring Boot 3.2.5

本文记录本次将「生产级智能客服系统」从 AgentScope Java **1.0.12** 迁移到 **2.0.0-RC4** 的全部改动、
API 映射、新增能力，以及**不能迁移**的能力及原因。

---

## 1. 依赖与构建

| 项 | 1.x | 2.0 |
| --- | --- | --- |
| 核心坐标 | `io.agentscope:agentscope`（聚合包） | `io.agentscope:agentscope-harness`（含 `agentscope-core`） |
| 版本管理 | 直接写死 `${agentscope.version}` | 引入 `agentscope-bom`（dependencyManagement / import） |
| 会话 Redis/MySQL | `agentscope`（内置 `core.session.redis/mysql`） | `agentscope-extensions-redis` / `agentscope-extensions-mysql` |
| 记忆 Mem0/ReMe/百炼 | `core.memory.*` 内置 | `agentscope-extensions-mem0 / -reme / -memory-bailian`（包名仍为 `io.agentscope.core.memory.*`） |
| RAG Dify/百炼/Simple | `core.rag.integration.*` 内置 | `agentscope-extensions-rag-dify / -rag-bailian / -rag-simple` |
| AG-UI / Higress / Studio / Nacos 提示词 | 内置 | `agentscope-extensions-agui / -higress / -studio / -nacos-prompt` |

> 说明：多数 extension 在 2.0 中**沿用 `io.agentscope.core.*` 包名**，因此 `LongTermMemoryProvider`、
> `KnowledgeProvider`、`AguiService`、`StudioConfigurer`、`HigressToolkitConfigurer` 等整合层代码
> **无需改动 import**，仅需补齐对应 extension 依赖即可编译运行。

构建（仓库根提供可移植 `settings-rc2.xml`：直连 Central、去除会拦截 Central 的 `external:*` 镜像）：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -s settings-rc2.xml clean test
```

---

## 2. 必须改动的 API 映射（Breaking Changes）

| 能力 | 1.x API | 2.0 API | 涉及文件 |
| --- | --- | --- | --- |
| 会话持久化 | `core.session.Session`（InMemory/Json/Redis/Mysql `Session`）+ `agent.saveTo/loadIfExists` + `SimpleSessionKey` | `core.state.AgentStateStore`（InMemory/JsonFile + extensions Redis/Mysql），按 `(userId, sessionId)` 由框架**自动加载/持久化** | `SessionConfig`、`CustomerServiceService`、`SessionStateManager`、`SessionHealthIndicator`、`MaintenanceScheduler` |
| 短期记忆装配 | `ReActAgent.Builder.memory(Memory)` | `.stateStore(AgentStateStore)` + `.defaultSessionId(...)` | `CustomerServiceAgentFactory` |
| 智能上下文压缩 | `core.memory.autocontext.AutoContextMemory` | Harness `CompactionConfig` + `HarnessAgent.compaction(...)` | `ContextMemoryFactory` |
| 任务规划 | `core.plan.PlanNotebook` + `InMemoryPlanStorage` | **Plan Mode**：`HarnessAgent.enablePlanMode()` / `enterPlanMode(ctx)` | `CustomerServiceAgentFactory`、`HarnessAgentFactory` |
| 多 Agent 编排 | `core.pipeline.Pipelines.fanout/sequential` | 用 Reactor 直接编排（`Flux.flatMap` / `Mono` 链）；或 HarnessAgent `subagent` | `MultiAgentOrchestrator` |
| 调用入参 | `agent.call(Msg)` / `agent.stream(Msg, opts)` | `agent.call(text/List<Msg>, RuntimeContext)` / `agent.stream(List<Msg>, opts, RuntimeContext)` | `CustomerServiceService` |
| 代码执行技能 | `SkillBox.codeExecution().withRead().withWrite().enable()` | `SkillBox.setWorkDir(Path)` + `ReActAgent.Builder.skillCodeExecutionEnabled(true).skillWorkDir(Path)` | `CustomerServiceAgentFactory` |
| 状态键 | `core.state.SimpleSessionKey / SessionKey / StateModule` | 移除；状态以 `(userId, sessionId, key, State)` 表达，`Msg` 已实现 `State` | 多处 |

### 2.0 状态模型要点

- **Agent 无状态**：单实例即可并发服务多租户多会话，状态经 `RuntimeContext(userId, sessionId)` + `AgentStateStore` 流转。
- 本项目把「会话 ID」映射为 `userId = 租户`（`tenantA:conv-1` 取 `tenantA`）、`sessionId = 完整会话 ID`，见 `CustomerServiceAgentFactory#contextFor`。
- 因此 `CustomerServiceService` 删除了手工 `saveTo/loadIfExists` 与「淘汰即落盘」逻辑，热 Agent 缓存仅用于摊薄装配开销。

---

## 3. 新增的 2.0 能力（按文档「Building Blocks / Harness」补齐）

| 能力 | 落地 | 配置开关 |
| --- | --- | --- |
| **Permission System** | `PermissionConfig` 产出 `PermissionContextState`，注入主 `ReActAgent`（`.permissionContext(...)`），与 `HumanApprovalHook` 形成「声明式权限 + 编程式确认」双层闸门 | `customer-work.harness.permission.*` |
| **Compaction（上下文压缩）** | `ContextMemoryFactory.createCompaction()` → `HarnessAgent.compaction(...)` | `customer-work.context.*` |
| **Plan Mode（只读规划期）** | `HarnessAgentFactory` 经 `HarnessAgent.enablePlanMode()` 启用 | `customer-work.harness.plan-mode.*` |
| **Workspace / Sandbox** | `HarnessAgent.workspace(Path)` 隔离工作区（文件工具 / 代码执行根） | `customer-work.harness.workspace-dir` |
| **Subagent（子智能体）** | `HarnessAgentFactory` 把订单/售后/知识库专家经 `subagentFactory(...)` 注册为子智能体 | `customer-work.harness.subagent.enabled` |

统一架构：`HarnessAgent.Builder.fromAgent(ReActAgent)` 复用内层 ReActAgent（模型/工具/长期记忆/RAG/Skill/Hook/权限），
再叠加上述 Harness 能力，避免「核心 vs Harness」二选一。见 `HarnessAgentFactory`。

---

## 4. 不能迁移的能力及原因

| 能力 | 1.x API | 原因 | 处置 |
| --- | --- | --- | --- |
| **实时 TTS 语音合成** | `core.hook.TTSHook` + `core.model.tts.DashScopeRealtimeTTSModel` | 2.0 核心已下线 TTS（"Core no longer ships TTS"），不再内置实时语音模型与 Hook | `TtsHookProvider` 保留为**文档化空实现**（`create()` 恒返回空，开启时日志提示）；如需 TTS，请在网关/前端直连厂商实时语音 SDK |
| **PlanNotebook（结构化任务清单存储）** | `core.plan.PlanNotebook` + `plan.storage.*` | 2.0 用 **Plan Mode**（只读规划 markdown + 获批写入）替代，理念从「结构化子任务存储」转为「规划/执行两阶段」 | 移除 PlanNotebook 装配，改用 `HarnessAgent.enablePlanMode()`；语义不完全等价，详见第 3 节 |
| **Pipelines 编排原语** | `core.pipeline.Pipelines.fanout/sequential` | 2.0 移除该工具类 | 用 Reactor 直接编排（等价行为），或用 HarnessAgent Subagent；见 `MultiAgentOrchestrator` |
| **SessionManager + StateModule 手工编排** | `core.state.StateModule` + `SessionManager.addComponent/saveSession` | 2.0 Agent 无状态、框架自动管理，逐组件 save/load 的编排模型被取消 | `SessionStateManager` 退化为对 `AgentStateStore` 的运维门面（exists/delete/listSessions） |

> 以上「不能迁移」均为**框架层主动移除/重构**导致，非本项目实现限制；处置方式已在对应类的 Javadoc 中标注。

---

## 5. 测试与验证

- 全量 `mvn -s scripts/settings-rc2.xml test` 四模块 **BUILD SUCCESS**；starter 169 项用例通过，Redis/MySQL/百炼集成测试在无对应服务时按 `assumeTrue`/`@EnabledIfEnvironmentVariable` **自动跳过**。
- 会话持久化往返测试改为基于 `AgentStateStore` + `Msg`（`Msg` 在 2.0 实现 `State`）。
- 新增 `PermissionConfigTest`、`HarnessAgentFactoryTest` 覆盖 2.0 新能力装配。

## 6. 环境说明（构建注意）

部分开发机全局 `~/.m2/settings.xml` 配置了 `<mirrorOf>external:*</mirrorOf>` 镜像（如内网 nexus 或已停服的 oschina），
会拦截 Maven Central 导致 RC4 传递依赖拉取失败。仓库根的 `settings-rc2.xml`：去除 external 镜像、直连 Central，
**仅本次构建使用**（`mvn -s settings-rc2.xml ...`），不影响全局配置。如需复用本机已有本地仓库缓存以加速，
可在该文件加 `<localRepository>…</localRepository>`。
