# AgentScope Java 1.x → 2.0 迁移说明

> 当前依赖：`io.agentscope:agentscope-harness:2.0.0`（GA 正式版，经 `agentscope-bom` 统一管理）
> 迁移路径：`main`（升级前 `io.agentscope:agentscope:1.0.12`）→ `rc2.0`（2.0.0-RC4，已冻结为历史存档）
> → `ga2.0`（2.0.0 GA 开发分支）→ **现行分支：`main`**（`ga2.0` 已并入，后续新工作直接基于 `main`）
> JDK 17 / Maven 3.9+ / Spring Boot 3.2.5

本文第 1~8 节记录的是 **1.0.12 → 2.0.0-RC4** 首轮迁移的全部改动、API 映射、新增能力，以及**不能迁移**的
能力及原因——这部分是历史记录，原样保留。**RC4 → GA（2.0.0 正式版）** 的后续升级改动见文末新增的
[「9. RC4 → GA 升级」](#9-rc4--ga-升级)一节。

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

构建（仓库根提供可移植 `settings-central-direct.xml`：直连 Central、去除会拦截 Central 的 `external:*` 镜像）：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -s settings-central-direct.xml clean test
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

- 全量 `mvn -s scripts/settings-central-direct.xml test` 四模块 **BUILD SUCCESS**；starter 169 项用例通过，Redis/MySQL/百炼集成测试在无对应服务时按 `assumeTrue`/`@EnabledIfEnvironmentVariable` **自动跳过**。
- 会话持久化往返测试改为基于 `AgentStateStore` + `Msg`（`Msg` 在 2.0 实现 `State`）。
- 新增 `PermissionConfigTest`、`HarnessAgentFactoryTest` 覆盖 2.0 新能力装配。

## 7. rc2.0 能力补全（对照 2.0 三大支柱，缺口已闭合）

在首轮迁移基础上，进一步补齐 2.0 官方概览页所列、首轮未落地的能力：

### 7.1 Middleware 取代松散 Hook（底层框架升级）
8 个业务 Hook 全部从 deprecated `core.hook.Hook` 迁移到 2.0 五段 `MiddlewareBase`
（`com.richard.fyoung.customerwork.core.middleware`），并经 `.middleware()` 织入：

| 中间件 | 段 | 承接的 1.x Hook |
| --- | --- | --- |
| `ToolGuardMiddleware` | `onActing` | ToolGuardHook（入参注入 / 数值钳制） |
| `DynamicOptionsMiddleware` | `onModelCall` | DynamicGenerateOptionsHook（精确档参数） |
| `MaskingMiddleware` | `onAgent` | MaskingHook（出站脱敏 AgentResultEvent） |
| `ObservabilityMiddleware` | `onReasoning`+`onActing`+`onAgent` | ObservabilityHook（指标 / 日志 / 错误） |
| `AuditMiddleware` | `onActing`+`onAgent` | AuditHook（审计轨迹） |
| `LatencyMiddleware` | `onAgent`/`onReasoning`/`onActing` | LatencyHook（分段延迟 Timer） |
| `SelfCorrectionMiddleware` | `onAgent` | SelfCorrectionHook（检测+告警，详见下） |
| `HumanApprovalMiddleware` | `onActing` | HumanApprovalHook（观测，闸门交 Permission） |
| `TenantContextMiddleware` | `onSystemPrompt` | 新增（第五段：租户上下文注入） |

- `pluggable` 注入由 `ObjectProvider<Hook>` → `ObjectProvider<MiddlewareBase>`；框架 `JsonlTraceExporter` 仍走 `.hook()`。
- **语义降级说明**：1.x `SelfCorrectionHook.gotoReasoning(强制重新推理)` 与 `HumanApprovalHook.stopAgent()`
  在中间件模型下无等价语义 —— "涉资金硬约束 / 工具放行闸门"已上升为框架原生 **Permission System**（ask/deny 规则），
  两个中间件退化为"检测 + 告警"，与 Permission 形成双层。
- `GlobalHookRegistry` 保留：系统级热插拔在 2.0 仍只有 `AgentBase.addSystemHook` 一种 API。

### 7.2 Harness 深度能力（Harness 工程化）
`HarnessAgentFactory` 经 `HarnessAgent.Builder.fromAgent(...)` 叠加（均 `customer-work.harness.*` 配置化）：

| 能力 | 落地 | 开关 |
| --- | --- | --- |
| 分层记忆（MEMORY.md + consolidation） | `MemoryConfig.builder().model(...).build()` | `harness.memory-enabled` |
| 超大工具结果落盘 | `ToolResultEvictionConfig.defaults()` | `harness.tool-result-eviction-enabled` |
| 技能自进化 | `enableSkillManageTool(true)` + `SkillCuratorConfig.defaults()` | `harness.skill-curator-enabled` |
| Plan 文件持久化 | `planFileDirectory(workspace/plans)` | 随 `harness.plan-mode.enabled` |
| 额外上下文文件注入 | `additionalContextFile(...)` | `harness.additional-context-file` |
| org 维度多租户 | `RuntimeContext.put("org", ...)` KV 命名空间 | `harness.org` |

### 7.3 安全沙箱执行（企业级分布式部署）
`HarnessAgentFactory#applySandbox` 按 `harness.sandbox.mode` 选择文件系统隔离：

| 模式 | Spec | 依赖 |
| --- | --- | --- |
| `local` | `LocalFilesystemSpec`（子进程隔离 + 超时） | Harness 内置 |
| `docker` | `DockerFilesystemSpec`（容器隔离 + 镜像/资源限制） | Harness 内置 |
| `none` | 不隔离（默认） | — |

- 隔离粒度 `IsolationScope`：session / user / agent / global。
- 快照跨进程恢复由 `SandboxLifecycleMiddleware` + `SessionSandboxStateStore` 在配置沙箱后自动管理。
- **远端沙箱**（Kubernetes / e2b / Daytona / AgentRun）需额外引入对应
  `agentscope-extensions-sandbox-{kubernetes,e2b,daytona,agentrun}`，本仓库默认未引入（按需启用）。

---

## 8. 环境说明（构建注意）

部分开发机全局 `~/.m2/settings.xml` 配置了 `<mirrorOf>external:*</mirrorOf>` 镜像（如内网 nexus 或已停服的 oschina），
会拦截 Maven Central 导致 AgentScope 及其传递依赖拉取失败。仓库提供两份可移植 Maven settings（该文件与
AgentScope 版本无关，早期名为 `settings-rc2.xml`，因职责就是"直连 Central、绕过镜像"、与版本解耦，已更名为
`settings-central-direct.xml`）：
- **仓库根 `settings-central-direct.xml`**：可移植版，不指定本地仓库，`mvn -s settings-central-direct.xml ...` 直连
  Central，适合 CI / 他人机器。
- **`scripts/settings-central-direct.xml`**：本机加速版，`<localRepository>` 指向本机 mavenjar 复用缓存。因 Maven
  会合并全局+用户两处 `activeProfiles`，本机若全局激活了镜像 profile，须 `-gs` 与 `-s` **同传**本文件才能真正
  覆盖：`mvn -gs scripts/settings-central-direct.xml -s scripts/settings-central-direct.xml ...`。

---

## 9. RC4 → GA 升级

> 升级时间：2026-07-10（AgentScope Java 2.0.0 GA 发布当日）｜ 迁移分支：`rc2.0`（2.0.0-RC4）→ `ga2.0`（2.0.0 GA）
> `rc2.0` 分支本身**保持不变**、冻结为历史存档；`ga2.0` 是从 `rc2.0` 切出的新分支。

### 9.1 方法论：源码 diff，不靠 release notes 猜

AgentScope Java 官方 Release Notes 对"2.0 系列"的描述偏总览性质，容易和"RC4 相对之前 RC 的增量"混淆。
本次升级改为**直接拉取 upstream 仓库、用 `git worktree` 把 RC4 tag 与 v2.0.0 tag 的源码摆在一起 `diff -u`**，
逐个核对本项目实际用到的类：

| 类/接口 | RC4 → GA 差异 |
| --- | --- |
| `EventType` / `Event` | 字节级无变化 |
| `PermissionMode` / `PermissionContextState` | 字节级无变化 |
| `MiddlewareBase` | 字节级无变化 |
| `McpClientWrapper` | 字节级无变化 |
| `StreamOptions` / `ThinkingBlock` | 字节级无变化 |
| `agentscope-extensions` 的 `MysqlAgentStateStore` | 字节级无变化 |
| `McpClientBuilder` / `Toolkit` / `ChatModelBase` / `GenerateReason` / `AgentEventType` | 仅新增方法/枚举值，原有签名不变 |
| `AgentBase` / `ReActAgent` / `HarnessAgent` | 仅内部接线变化（[#2086](https://github.com/agentscope-ai/agentscope-java/pull/2086)：`seedSystemMsg`/`applySystemPromptMiddlewares` 由同步改响应式，`Msg`→`Mono<Msg>`、`String`→`Mono<String>`），不影响未覆写这些受保护方法的调用方代码 |

结论：**对本项目的实际调用面而言，RC4→GA 风险极低**——不是"marketing 意义上的 2.0 系列新特性都是新的"，而是这次具体的 RC4→GA 增量本身很小。

### 9.2 唯一的破坏性改动：内置模型实现拆分为独立扩展模块

**这是本次升级唯一需要改代码的地方。**GA 起，内置的 5 家模型实现（`AnthropicChatModel` /
`DashScopeChatModel` / `GeminiChatModel` / `OllamaChatModel` / `OpenAIChatModel`）从 `agentscope-core`
拆出，独立为 5 个 Maven 模块：

| 项 | RC4 | GA |
| --- | --- | --- |
| 归属 artifact | `agentscope-core`（内含） | `agentscope-extensions-model-{dashscope,openai,anthropic,gemini,ollama}`（独立坐标，版本由 `agentscope-bom` 统一管理，无需显式写版本号） |
| 包名 | `io.agentscope.core.model.*` | `io.agentscope.extensions.model.{provider}.*` |
| Builder API（`apiKey`/`modelName`/`stream`/`generateOptions`/`baseUrl`/`enableSearch`/`enableThinking` 等） | — | **与 RC4 完全一致，零方法签名变化** |

> ⚠️ **验证方式的坑**：仅看 upstream 源码树是不够的——RC4 源码树里 `io.agentscope.extensions.model.*` 这套
> 目录布局其实**已经存在**，但 RC4 **实际发布出来的 `agentscope-core` jar**（用 `unzip -l` 核实）里这些模型类
> 打包在 `io.agentscope.core.model.*` 下，与源码树的目录结构不一致。只看源码路径会误判"没有破坏性变更"，
> 必须直接解包对比**真实发布的 jar**。

**改动内容**（4 个文件，均为纯 import 语句调整，Builder 调用代码零改动）：
- [`pom.xml`](../pom.xml)：`agentscope.version` 由 `2.0.0-RC4` 改为 `2.0.0`。
- [`customer-work-starter/pom.xml`](../customer-work-starter/pom.xml)：新增 5 个
  `agentscope-extensions-model-*` 依赖声明（不写版本号，靠 `agentscope-bom`）。
- `ModelConfig.java` / `ModelConfigTest.java` / `BailianIntegrationTest.java`（均在 starter 模块）、
  `AdminModelFactory.java`（`customer-admin-server` 模块）：5 个模型类的 import 语句从
  `io.agentscope.core.model.*` 改为 `io.agentscope.extensions.model.{provider}.*`。

全仓 `grep -rln "io\.agentscope\.core\.model\.\(AnthropicChatModel\|DashScopeChatModel\|GeminiChatModel\|OllamaChatModel\|OpenAIChatModel\)"` 核实过，改动前后各出现且仅出现在这 4 个文件。

### 9.3 已修复的已知缺陷

`fallbackModel` 内置装饰器的已知 bug（[#1850](https://github.com/agentscope-ai/agentscope-java/issues/1850)，
"实际不工作"）已由 [#1851](https://github.com/agentscope-ai/agentscope-java/pull/1851) 于 2026-07-06 修复并
合入 GA（早于 2026-07-10 GA 发布）。本项目仍保留自研 `FallbackChatModel`（2.2 起由 `FailoverModel` 替代），
原因是要与 `ResilientChatModel`（退避重试）组合叠加，而非规避该缺陷；见 [功能与配置全量参考 §6.13b](功能与配置全量参考.md)。

### 9.4 验证结果

- **编译**：`customer-work-starter`、`customer-admin-server` 均 `mvn clean compile`/`clean test-compile`
  通过。踩坑记录：不带 `clean` 的增量编译会因 Maven 增量编译器不检测 classpath/依赖版本变化而**误报成功**，
  验证依赖版本变更后必须用 `clean compile`/`clean test-compile`。
- **单元测试**：全仓 `mvn clean test` **全绿**（starter 362 + app 13 + customer-channel 8 +
  `customer-admin-server` 127 = 510，0 失败 0 错误，1 跳过为需真实 API Key 的联调测试）。
- **过程中定位并修复了 2 个既有 bug**（与 AgentScope 升级本身无关，最初被"本机 MySQL 密码不匹配"这个更表层
  的错误现象掩盖，一路排查才发现）：
  1. **`CustomerWorkProperties.java` JDBC URL 的 `characterEncoding=utf8mb4` 非法**——这是 MySQL 侧字符集名，
     Connector/J 的 `characterEncoding` 连接参数要的是 Java NIO Charset 名（合法值应为 `UTF-8`），驱动直接
     拒绝连接（`Unsupported character encoding 'utf8mb4'`）。改为 `characterEncoding=UTF-8`；4 字节 Unicode
     支持不受影响，靠的是各表 `DEFAULT CHARSET=utf8mb4`（连接层与存储层是两回事）。
  2. **`JdbcAuditSink.QUERY_BY_SESSION_SQL` 的 `ESCAPE` 子句反斜杠转义少了一层**——Java 源码 `"ESCAPE '\\'"`
     只产生一个反斜杠字符，MySQL 解析该 SQL 文本时把这个反斜杠当成转义符去转义紧跟的右单引号，导致字符串
     字面量未正常闭合、连带吞掉后面 `LIMIT ?` 的问号占位符，驱动报 `Parameter index out of range`。改为
     `"ESCAPE '\\\\'"`（SQL 文本层两个反斜杠 = 一个正确转义、正常闭合的字面反斜杠字符）。
  两处改动均定位在验证 GA 升级本身"编译/测试没问题"这一诉求的过程中，属于顺带修复，不改变任何业务行为，
  只是让此前被环境问题掩盖、从未被真实执行到的代码路径首次跑通。
- **本机开发环境凭据对齐**（纯环境配置，非代码改动）：本机 MySQL root 密码由 `root` 改为
  starter 模块多个 `Jdbc*Test`（无环境变量覆盖机制，纯硬编码）期望的 `root`；本机 Redis 设置
  `requirepass=123456`（匹配 `RedisSessionPersistenceTest`）。**连带影响**：`customer-admin-server` 的
  `application.yml` 默认 `ADMIN_MYSQL_PASSWORD` 为 `root`，随本机密码变更而失配，运行其测试/应用
  需显式 `export ADMIN_MYSQL_PASSWORD=root`（已验证生效）。
- **第二节固化的两个 P0 探针测试**（`MiddlewareInvocationVerificationTest` / `TenantIsolationVerificationTest`，
  见 [生产就绪评估.md](生产就绪评估.md)）在 GA 下重跑全绿，确认框架行为未漂移。
- **issue 全量重新核对**：见下方「10. GA issue 全量重新核对」——已完成，不再是未覆盖项。
- **仍未覆盖项**：`docs/详细技术文档.md` 等文档中除已更新章节外的深层架构描述、历史测试计数等细节，未逐字重新校验。

---

## 10. GA issue 全量重新核对

> 本节是 RC4→GA 升级的收尾工作，与第 9 节（代码/依赖层面的升级）是两件独立的事：第 9 节回答"代码还能不能编译/跑通"，
> 本节回答"[生产就绪评估.md](生产就绪评估.md) 里锚定在 RC4 时点的结论，在 GA 下还成不成立"。

### 10.1 方法

1. **逐一核对文档已引用的 29 个具体 issue 编号**：用已抓取的当前全量 open issues 列表本地比对（issue 编号
   若不在当前 open 列表中，说明已关闭），命中 3 个后逐个调用 GitHub timeline API 找到关联的修复 PR，核实
   PR 合并时间早于 2.0.0 GA 发布时间（2026-07-10T03:10:36Z），确认修复真正随 GA 发布，而不是"合了主干但
   没赶上这个 tag"。
2. **全量拉取仓库当前 open issues**：分页拉取（`state=open&per_page=100`），过滤掉 PR（`pull_request` 字段），
   得到当前 open issues 总数 **402**（RC4 时点为 120，增长约 3.4 倍，判断为 GA 发布后正常的问题反馈增长，
   非本项目风险信号本身）。
3. **关键词相关性筛选**：对 RC4 发布日（2026-06-18）之后新提交的 issue，按本项目实际用到的能力域做关键词
   命中（middleware/session/subagent/sandbox/tool guard/permission/compaction/SSE/structured output/
   DashScope/fallback/MCP/skill/tenant/interrupt/HarnessAgent 等），命中 21 条，逐条读取标题+正文摘要，
   甄别出 3 条对本项目有实质参考价值的新风险。

### 10.2 结论汇总

| 类别 | 结果 |
| --- | --- |
| 已确认修复合入 GA | [#1850](https://github.com/agentscope-ai/agentscope-java/issues/1850)（fallbackModel 不工作）、[#1979](https://github.com/agentscope-ai/agentscope-java/issues/1979)（fat-jar 下 ClasspathSkillRepository 加载失败）、[#1968](https://github.com/agentscope-ai/agentscope-java/issues/1968)（中断后状态未保存），均已核实修复 PR 合并早于 GA 发布 |
| 仍 open（无变化） | 原文档引用的其余 26 个 issue，含 #1954/#1953/#1700/#1911（子智能体，按用户指示本轮不处理）、#1683（HarnessAgent 中断转发，与已修复的 #1968 是两个不同 bug，此前文档误合并为一行，已拆分）等 |
| GA 后新发现（新增计入 [生产就绪评估.md §六](生产就绪评估.md)） | [#1988](https://github.com/agentscope-ai/agentscope-java/issues/1988)/[#1989](https://github.com/agentscope-ai/agentscope-java/issues/1989)（中间件回调内 `interrupt()` 跨 session 静默失效，本项目中间件未触发但作边界风险记录）、[#1906](https://github.com/agentscope-ai/agentscope-java/issues/1906)（框架 `SkillBox#uploadSkillFiles()` 伴生文件 bug，本项目自研上传逻辑未触发）、[#2075](https://github.com/agentscope-ai/agentscope-java/issues/2075)（MCP SDK 安全漏洞待修，本项目 MCP 默认关闭） |
| 架构决策被新证据印证 | [#2024](https://github.com/agentscope-ai/agentscope-java/issues/2024)（AG-UI CopilotKit HITL 语义 bug）印证了 [生产就绪评估.md §四](生产就绪评估.md) 中"HITL 不绑定框架 AG-UI 确认机制"的架构决策是正确判断 |

### 10.3 诚实边界（未做的事）

- **未逐条人工阅读全部 402 个 open issue 原文**——工作量与首轮 120 个评估相当，本次用关键词相关性筛选替代，
  可能遗漏未命中关键词但仍相关的 issue。
- **未做"RC4 之后新提交且已关闭"的全量搜索**——只核对了文档历史引用过的 29 个 legacy issue 的关闭情况，
  可能遗漏其他已随 GA 修复、但未被本文档历史引用过的 bug。
- 详细的逐条结论更新见 [生产就绪评估.md](生产就绪评估.md) 第四～七节。
