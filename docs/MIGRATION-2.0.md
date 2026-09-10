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

---

## 10. 2.0.2 → 2.0.3 升级（2026-09-10）

### 10.1 两处编译期破坏，都在 admin 侧

starter 零改动。

**`AgentRunner`**：`stream(List, AgentRequestOptions)` → `streamEvents(...)`，返回类型从
`Flux<Event>`（A2A 协议事件）换成 `Flux<AgentEvent>`（框架细粒度事件），**协议转换收归框架**。

这正是 `AdminAgentRunner` 那段注释预告的时机——它当时写着「`AgentRunner#stream` 的返回类型被框架
写死为 `Flux<Event>`，就是那个废弃类型本身；框架自带的 `BaseReActAgentRunner` 也仍在调
`agent.stream(msgs)`——A2A 这一层框架自己都没迁……等框架把 A2A 层迁到细粒度事件后再跟进」。
2.0.3 迁了，于是跟进：内部改调 `agent.streamEvents(msgs, ctx)`，并去掉 `StreamOptions`
的事件类型过滤（新接口下由框架决定哪些事件进协议包，调用方自行裁剪会与框架的去重/拼包语义打架）。

**`TaskRepository`**：接口新增 `public shutdown()`，与项目那个包级私有的 `@PreDestroy` 方法撞名
（报「正在尝试分配更低的访问权限」）；`removeTask` / `clear` 不再是接口方法。
前者提升为 public 并标 `@Override`，后者去掉 `@Override` 但保留方法本身（管理台仍在用）。

### 10.2 上游 #1683 修复：探针如期变红

`HarnessAgentInterruptForwardingProbeTest` 断言的是「框架**还没**修 #1683」，
失败消息写着「出现即说明框架已修复，需复核绕行方案」。升级当天它立刻变红——
**这正是这类「记录现状」的探针被设计出来要做的事**。

2.0.3 给 `HarnessAgent` 补齐了全部四个 session-aware `interrupt` 重载。反编译确认
`interrupt(RuntimeContext)` 的实现就是 `delegate.interrupt(ctx)`——与项目原绕行**行为完全等价**，
所以 `ChatService#interrupt` 改用新 API 是零风险的，收益是不再依赖内部结构。

`AgentStateAccessor` 那处的 `getDelegate()` **刻意保留**：`HarnessAgent.getAgentState()`
只有无参版本，取不到按会话的状态。

### 10.3 需要留意的行为变更（本次未处理）

| 变更 | 影响 |
|---|---|
| agent state 加载失败**不再静默替换**为新会话（#2760） | 原本靠静默降级跑着的部署会开始报错——这是好事，但升级后可能出现新的失败告警 |
| `ThinkingBlock` token 按真实内容计数（#3009） | token 统计数字会变，**影响配额判定与账单金额** |
| `Retry empty final responses`（#2755） | 推理模型把答案写进 `reasoning_content` 时会重试，可能多一次模型调用 |
| `OkHttpTransport` SSE 背压修复（#2963） | 流式首字延迟的**收益**，无需改动 |
| `ToolResultBlock.metadata` 通过细粒度事件传播（#2315） | **对本项目无用，见 11.4 的更正**——文本标记方案仍是必须的 |

### 10.4 一个从 2.0.2 起就存在、此前被漏看的事实

编译告警显示框架已把**整套 RAG 与长期记忆 API 标记为 `forRemoval`**：

| API | 项目引用处 |
|---|---|
| `rag.Knowledge` | 37 |
| `memory.LongTermMemory` | 32 |
| `hook.Hook` | 20 |
| `hook.recorder.JsonlTraceExporter` | 11 |
| `rag.model.RetrieveConfig` | 10 |
| `tracing.TracerRegistry` | 7 |

**这不是 2.0.3 引入的**——逐个反编译对比确认 2.0.2 的 class 文件里就带着 `Deprecated` 标记，
是批次二升级到 2.0.2 时漏看了编译告警。2.0.3 的 release notes 也没有 Deprecated 段、
未给出替代方案。

**待办**：这几套 API 一旦在某个版本真被移除，项目会直接编译不过。下次升级前应当先查清
框架给出的替代路径（`Knowledge` 那 37 处可能随知识库改用外部 kb-rag 而自然消解，
但长期记忆的 32 处、Hook 的 20 处仍需迁移方案）。

---

## 11. 2.0.3 能力采纳（2026-09-10）

第 10 节做的是「让它编译过、跑起来」，本节做的是「把 2.0.3 真正带来的东西用上」。

### 11.1 2.0.3 到底改了什么：按 jar 的公开 API 逐项 diff

不看 release notes，把 2.0.2 与 2.0.3 两套 jar 全部解开，对每个类（**含内部类与 Builder**）
出一遍 `javap` 签名再逐行比对。这个方法在 9.1 节已经用过一次，本次补上一个教训：
**第一遍我过滤掉了带 `$` 的类名，于是漏掉了 `ReActAgent$Builder`**——而 2.0.3 最重要的新入口
`conflictPolicy(...)` 恰恰只在 Builder 上。Builder 是框架最主要的 API 面，绝不能被当成内部类跳过。

`agentscope-core` 的结论：**零删除、零签名变更**，纯新增。

| 新增 | 内容 |
|---|---|
| 状态版本化（主线特性） | `AgentStateStore` 加 `supportsVersioning()` / `getVersioned()` / `saveIfVersion()` / 常量 `UNVERSIONED`；新类 `VersionedState` / `ConflictPolicy` / `ConcurrentSessionModificationException`；`ReActAgent.Builder#conflictPolicy` 与 `ReActAgent#getStateConflictCount()` |
| `FinalAnswerFilterMiddleware` | 抑制「产生了工具调用那一轮」的中间文本 |
| ToolResult 四个事件加 `metadata` 构造参数 | #2315 |
| `Toolkit#addToolToGroup(String, String)` | 运行时把已注册工具并入某个组 |
| `ToolResultBlock.error(String, String)` | 错误结果的静态工厂 |
| `ExceptionUtils.containsInterruptedException` | 中断判定 |
| `LegacyStateLoader` 两个带 `PermissionContextState` 的重载 | 旧会话加载 |
| `GracefulShutdownManager#checkAndClearShutdownInterruptedForState` | 优雅停机 |
| `Msg.METADATA_EXTERNAL_EXECUTION_REQUEST_REPLY_ID` | AG-UI 外部执行 |

其余模块：`extensions-mysql` / `extensions-redis`（三种客户端）各自实现了版本化三件套，
redis 侧新增 `RedisStateVersionSupport`（Lua 脚本做 CAS）；`extensions-a2a-server` 的
`AgentRunner.stream` → `streamEvents`（第 10 节已处理）；`extensions-a2a-client` 新增
`HintBlockParser`；`harness` 大面积新增（Team 多智能体协作、Transcript 存储、Artifact 交付、
SessionTurnGate、PeriodicGate、SkillUsageBackend、WebTools）；上游还多了两个全新模块
`extensions-jdbc`（通用 JDBC 状态存储 + H2/MySQL/Postgres/SQLite 四方言）与
`extensions-aistio`（控制平面 gRPC 数据面）。

### 11.2 状态版本化：升级后**默认就在生效**，且有一个必须先处理的库结构前提

这一条是本次升级里唯一有生产风险的。

**默认生效**：`ReActAgent` 读状态走 `getVersioned`、写回走 `saveIfVersion`，是否走这条路径由
`store.supportsVersioning()` 决定——而 `MysqlAgentStateStore` 的这个方法**硬编码返回 true**
（字节码就是 `iconst_1; ireturn`）。`Builder#conflictPolicy` 不设时默认 `OVERWRITE`。
也就是说，不做任何配置，升级后每一次会话状态写入都在做 CAS。

**库结构前提**：CAS 依赖表上的 `version` 列。框架在 `MysqlAgentStateStore` 的**构造器**里
执行 `ensureVersionColumn()` 自动补列，这一步在 `autoCreate` 分支**汇合之后**，
即 `autoCreate=false` 时照样执行；补列失败会把 `SQLException` 包成 `RuntimeException` 抛出，
**构造器失败 = 应用起不来**。

这两条都做了实测（临时库 + 只授 DML 权限的账号，验完即删），不是只有反编译推断：

```
# autoCreate 传 false，表上没有 version 列
构造前 version 列: false
构造后 version 列: true          ← 框架照样补了列
supportsVersioning(): true

# 换成只有 SELECT/INSERT/UPDATE/DELETE 权限的账号
构造前 version 列: false
Exception in thread "main" java.lang.RuntimeException: Failed to ensure version column on table: agentscope_sessions
    at ...MysqlAgentStateStore.ensureVersionColumn(MysqlAgentStateStore.java:201)
    at ...MysqlAgentStateStore.<init>(MysqlAgentStateStore.java:178)
Caused by: java.sql.SQLSyntaxErrorException: ALTER command denied to user 'cw_dml_only'@'...'
```

两种部署形态因此有不同的动作：

| 库 | 表 | 归谁管 | 升级动作 |
|---|---|---|---|
| `customer_admin` | `ai_chat_session_state` | Flyway（V4 建表，`createIfNotExist=false`） | **已加 `V102__agent_state_optimistic_version.sql`**，先由迁移补列，框架那一步查到列已存在即跳过 |
| `agent_scope_customer_work` | `agentscope_sessions` | 框架自建，无迁移、不进结构快照 | 应用账号有 DDL 权限时框架自动补列；生产按 DBA 模式部署的，用 `mysql/01-agent-scope-customer-work/customer-work-agent-state-version-alter.sql` 先手工加列 |

让框架自己去改一张归 Flyway 管的表有两个坏结果，V102 的抬头注释里写了：结构与迁移产物不一致
（结构快照门禁会红，而红的原因与任何人的改动无关），或者应用账号只有 DML 权限时直接起不来。

**项目侧采纳**：

- 冲突策略做成配置项 `customer-work.agent.state-conflict-policy`，默认 `OVERWRITE`
  （保持框架默认，也就是保持升级前「最后写入者赢」的行为）。设定点在
  `AgentGovernanceAssembler` 一处——它是构建期设定，散到各个建 Agent 的入口里各写一遍
  必然重演「能力只接在一条路径上」。
- 冲突计量 `customerwork.agent.state.conflicts`（`AgentStateConflictMetrics`）。
  **`OVERWRITE` 会把 CAS 失败悄悄吸收掉**：重读最新版本再覆盖，不抛异常、不打日志，
  会话状态互相覆盖这件事发生了却毫无痕迹。采集点选在 `AgentResourceCloser.closeQuietly`
  ——那是全部 11 处 Agent 释放的唯一入口，而 `getStateConflictCount()` 是 Agent 实例级累计值，
  Agent 又按会话缓存，释放时读一次即「这个会话一共冲突了几次」，不重不漏。
  这个指标顺带是 `SessionLock` 在 Redis 故障时保护性降级进程内之后，
  唯一能看出「串行锁其实已经不管用了」的间接证据。
- **修掉一个被静默吞掉的能力**：`SandboxSafeAgentStateStore`（Docker 沙箱模式下装饰 MySQL store）
  没有转发新增的三个方法。它们**都带 default 实现**，所以不转发照样编译通过，
  只是 `supportsVersioning()` 恒返回默认的 `false`，被装饰的 MySQL store 支持版本化、
  套上壳之后框架就退回无版本写入。**接口每加一个 default 方法，所有装饰器就多欠一处转发**，
  而欠着不报错。`SandboxSafeAgentStateStoreVersioningTest` 用真实 store 验 CAS 语义
  （版本对得上才写、对不上返回 `UNVERSIONED` 且不覆盖）穿过装饰器后依然成立。
- admin 侧显式建 `AgentStateConflictMetrics` Bean（那个模块 `spring.autoconfigure.exclude`
  掉了 starter 自动装配）。不建的后果是无声：客服端能看到冲突、后台看不到——
  而后台的 VibeCoding 长任务与多标签页同会话恰恰更容易并发写状态。

### 11.3 FinalAnswerFilterMiddleware：接了，但默认关闭

反编译确认它的逐事件行为：按 `ModelCallStartEvent` 开一轮，缓冲 `TextBlock*` 三种事件；
该轮一旦出现同 `replyId` 的 `ToolCallStartEvent`，就把已缓冲的文本整段丢弃并对后续文本直接返回
`Flux.empty()`；直到 `ModelCallEndEvent` 且该轮没有工具调用，才把缓冲的文本一次性放出。

**代价是流式打字机效果完全消失**：即使那一轮就是最终答复，文本也要攒到 `ModelCallEndEvent`
才整段吐出来，用户会盯着空白等几秒。而客服场景里「好的，我帮您查一下」这类过渡语恰恰是
有价值的等待反馈，不是噪声。因此 `customer-work.agent.final-answer-filter-enabled` 默认 `false`，
只在非流式集成（如渠道机器人只取最终文本）里才值得打开。

顺序上它取框架默认 `order=1`，比本项目全部治理中间件（50~200）都内，
`AgentStateConflictPolicyAssemblyTest` 对此下断言：先由它决定这一轮的文本放不放，
放出来的那份再依次经过自我纠错、脱敏、敏感词等出站处理。框架哪天把它的 order 抬上来，
顺序会翻转成「先脱敏再决定丢不丢」，白做一遍且不报错。

### 11.4 更正：`ToolResultBlock.metadata` 事件传播对本项目无用

第 10.3 节曾写「引用回传现在有了更干净的替代路径」，**这个说法不成立**。

反编译确认 2.0.3 的 `ReActAgent$CallExecution` 的确会读 `ToolResultBlock.getMetadata()`
并 `withMetadata()` 填进三个 ToolResult 事件（2.0.2 没有这一步），传播链路本身是通的。
但源头没有 metadata：`KnowledgeRetrievalTools` 返回的是 `String`，框架据此构造 `ToolResultBlock`
时 metadata 为空。要用上这条路径得先改造工具返回类型，而那比 PR #180 现有的文本标记方案更重。
**文本标记方案保留。**

### 11.5 明确不采纳的部分及理由

| 能力 | 不做的理由 |
|---|---|
| `extensions-jdbc` 通用状态存储 | 项目已用 `extensions-mysql`，换过去零功能收益，只多一次存储层迁移风险 |
| `extensions-aistio` 控制平面 | 需要独立部署一套控制面服务并接 gRPC 数据面，是一个独立的架构决策，不是升级的一部分 |
| harness 的 Team 协作 / Transcript / Artifact 交付 / PeriodicGate | Harness 栈在本项目零调用方（见能力差距报告附录 D），先有调用方再谈接能力 |
| `Toolkit#addToolToGroup(String, String)` | 项目的工具分组在注册时就定好（`ToolRegistrar`），没有运行时改组的场景 |
| `ToolResultBlock.error(String, String)` | 项目工具一律返回 String 结果文本，没有直接构造 `ToolResultBlock` 的地方 |
| `ExceptionUtils.containsInterruptedException` | 全仓没有等价的自写判定可替换 |
| `LegacyStateLoader` 的新重载 | 项目没有旧格式会话要加载 |
| AG-UI 外部执行 / 权限确认事件转换器 | 项目的 AG-UI 用法不涉及外部执行与权限确认交互 |
| `McpServerRegistrationListener` | 在 harness 模块，项目 MCP 走 starter 的 `McpToolkitConfigurer`，不经 harness |

---

## 12. 2.0.0 GA → 2.0.3 累计变更全量清单

第 9~11 节是按升级批次写的（RC4→GA、GA→2.0.2、2.0.2→2.0.3）。本节给的是**跨三个版本的累计视图**：
从 `main` 当初的 2.0.0 GA 一路到 2.0.3，框架总共变了什么。

方法与 9.1 节一致：把 2.0.0 与 2.0.3 两套 jar 全部解开，对每个类（**含内部类与 Builder**）
出 `javap` 签名逐行比对。项目从未用过 2.0.1，故不单独区分 2.0.1 与 2.0.2 的贡献。

### 12.1 破坏性变更：只有两处，都在 A2A 与 Harness

`agentscope-core` **零删除、零签名变更**，纯新增。全部破坏性变更如下：

| 模块 | 变更 | 项目影响 |
|---|---|---|
| `extensions-a2a-server` | `AgentRunner.stream(...): Flux<Event>` → `streamEvents(...): Flux<AgentEvent>`；`AgentScopeAgentExecutor` 的三个事件处理器同步改签名（`handleEvent` 多一个 `Msg` 参数） | `AdminAgentRunner` 改调 `agent.streamEvents(msgs, ctx)`，见 10.1 |
| `harness` | `TaskRepository` 新增 `shutdown()`、`removeTask`/`clear` 不再是接口方法；`ProjectAwareOverlay` 与 `SandboxBackedFilesystem` 多处方法加 `RuntimeContext` 参数 | `MybatisTaskRepository` 的 `shutdown()` 提升为 public，见 10.1 |

`spring-boot-starter` / `extensions-rag-simple` / 各模型扩展：**零变更**。

### 12.2 core 新增能力（按主题归类）

| 主题 | 新增 API | 本项目状态 |
|---|---|---|
| **中间件排序** | `MiddlewareBase#order()`（2.0.0 没有） | **已采纳**：`MiddlewareOrders` 顺序契约 + `MiddlewareOrderContractTest`，24 个中间件此前全用默认值、顺序由 Bean 定义顺序偶然决定 |
| **状态乐观并发** | `AgentStateStore` 的 `supportsVersioning` / `getVersioned` / `saveIfVersion` / `UNVERSIONED`；`VersionedState` / `ConflictPolicy` / `ConcurrentSessionModificationException`；`ReActAgent.Builder#conflictPolicy`、`ReActAgent#getStateConflictCount/getConflictPolicy` | **已采纳**，见 11.2 |
| **最终答复过滤** | `FinalAnswerFilterMiddleware` | **已采纳但默认关闭**，见 11.3 |
| **上下文与状态缓存的显式清理** | `ReActAgent#clearContext(RuntimeContext)` / `clearContext(String,String)`、`clearStateCache()` 三个重载、`replacePermissionContext(String,String,PermissionContextState)` | 未采纳：项目按会话缓存 Agent 并整体释放（`AgentResourceCloser`），没有"留着 Agent 但清空它的上下文"的场景 |
| **第三方模型窗口推断** | `ModelContextWindows` 新增 `GLM` / `DEEPSEEK` / `KIMI` / `MINIMAX` 四张表 | **本批次采纳**，见 12.4 |
| **模型扩展辅助** | `ModelProviderSupport`（`stringOption` / `intOption` / `booleanOption` / `findAssignableComponent` / `firstNonBlank` / `trimToNull`） | 未采纳：项目不自研 Model 实现，只用框架的 `ChatModelFactory` |
| **HTTP 传输超时** | `HttpTransportConfig` 的 `responseTimeout` / `streamIdleTimeout` 及默认值 | 未采纳：项目的模型超时走 `model.retry.*` 与厂商 SDK 默认值；要接的话得先回答"流式空闲多久算卡死"，那是独立的一件事 |
| **事件元数据** | `AgentEvent#withMetadataEntry`、`METADATA_TASK_ID`、`METADATA_PARENT_SESSION_ID`；ToolResult 四个事件的 `metadata` 构造参数（#2315） | 未采纳，`metadata` 那条对引用回传无用，见 11.4 |
| **消息元数据** | `Msg#withMetadata(Map)`、`METADATA_CONFIRM_REQUEST_REPLY_ID`、`METADATA_EXTERNAL_EXECUTION_REQUEST_REPLY_ID` | 未采纳：项目的人工确认走自己的 `HumanApprovalMiddleware` + Permission ask，不经 AG-UI 的确认回传协议 |
| **工具** | `Toolkit#addToolToGroup(String,String)`、`ToolResultBlock.error(String,String)`、`AllToolsDeniedEvent` | 未采纳，理由见 11.5 |
| **优雅停机** | `GracefulShutdownManager#checkAndClearShutdownInterruptedForState` / `unbindStateSaver` | 未采纳：项目的停机链路（`GracefulShutdownRequestTrackingProbe`）不涉及按状态判定中断来源 |
| **旧会话加载** | `LegacyStateLoader` 的 `LegacyLoadResult` 与带 `PermissionContextState` 的重载 | 未采纳：项目没有 1.x 格式的存量会话 |
| **文本累加器改写** | `ReasoningContext#replaceAccumulatedText`、`TextAccumulator#replace` | 未采纳：项目的输出改写走中间件事件流（`SelfCorrectionMiddleware`），不直接操作累加器 |
| **中断判定** | `ExceptionUtils#containsInterruptedException` | 未采纳：全仓没有等价的自写判定可替换 |

### 12.3 扩展模块新增

- **`extensions-mysql` / `extensions-redis`**：三种 Redis 客户端（Jedis / Lettuce / Redisson）与 MySQL
  全部实现版本化三件套；Redis 侧新增 `RedisStateVersionSupport`（Lua 脚本做 CAS）与
  `RedisClientAdapter#evalScript`。
- **`extensions-a2a-client`**：`HintBlockParser` 与两个 hint metadata 键。
- **`extensions-agui`**：外部执行与权限确认的事件转换器、`AguiRequestBodyParser`、
  `AguiRuntimeContextResolver`（从 `agui-spring-boot-starter` 下沉过来）。
- **两个全新模块**：`extensions-jdbc`（通用 JDBC 状态存储 + H2/MySQL/Postgres/SQLite 四方言）、
  `extensions-aistio`（控制平面 gRPC 数据面）。
- **`harness` 新增 55 个类**，成规模的有六块：Team 多智能体协作（`TeamClient` / `TeamTool` /
  `TeamsMiddleware` / `TeamWakeups`）、Transcript 存储（文件系统与对象存储两种实现 +
  `TranscriptMiddleware`）、Artifact 交付（`ArtifactDeliveryTool`）、远程子智能体协议
  （`RemoteEventCodec` / `RemoteSubagentTransport` / `AgentProtocolTransport`）、
  跨副本协调（`PeriodicGate` / `SessionTurnGate` / `TurnLease`）、技能使用度量
  （`SkillUsageBackend` 两种实现），另有 `WebTools` 与 `HarnessPlatformTools`。
  **本项目 Harness 栈零调用方**，故整块不采纳，理由见能力差距报告附录 D。

### 12.4 顺带修正一条已过时的项目结论：第三方模型窗口

`CLAUDE.md` 里写着「框架的窗口推断表只收录各厂商**官方**模型名，`glm` / `deepseek` 这类
走 OpenAI 兼容协议接入的第三方模型一律返回 0」。**前半句现在不成立了**：
2.0.0 之后框架补上了 GLM / DEEPSEEK / KIMI / MINIMAX 四张表，`glm-5.2`、`deepseek-v4-pro`、
`kimi-k3`、`minimax-m3` 这些名字都在里面。

返回 0 这个现象仍然成立，但**理由变了**：`ModelContextWindows.lookup(modelName, table)` 的表由
调用方传，`OpenAIChatModel` 传的是 `OPENAI` 表——所以把智谱模型按 OpenAI 兼容端点登记时，
框架查的是 OPENAI 表，自然查不到。**是登记方式决定的，不是框架没收录。**

据此本批次给 `ModelProvider` 加了 `inferContextWindow(modelName)`：按真实厂商登记的部署，
运营没登记窗口时从对应的厂商表推断，推断不出返回 `null` 交回框架（**不是返回 0**——
把"推断不出来"写成"窗口是 0"正是上线认证曾经对所有第三方部署恒判失败的病根）。
Ollama 刻意没有表：本地部署的模型名由部署者自己起，任何硬编码清单都猜不中。

`AdminModelFactoryTest` 里原来那条钉住"glm-5.2 推断为 0"的用例**保留不动**，
并在旁边加了一条只差 provider 一个字段的对照用例——两条一起看才说明白 0 是怎么来的。
