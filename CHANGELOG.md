# Changelog

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/) 与 [Keep a Changelog](https://keepachangelog.com/zh-CN/)。

## [Unreleased]

### 入站防注入围栏 + 用户反馈闭环（P8）

安全防护与数据飞轮的第二条输入通道。

- **`PromptInjectionGuardMiddleware`（入站防注入围栏）**：落在 `onAgent`——`MiddlewareBase` 唯一"拦截
  整次 Agent 调用"的钩子。命中中英文常见注入/越狱模式（"忽略之前的指令"/"ignore the above
  instructions"/套取系统提示词/角色扮演绕过限制/`DAN` 等）即**不调用 `next`**，直接返回统一拒绝话术，
  不产生模型调用——省成本 + 防止注入内容触达推理。与 `ToolGuardMiddleware`（工具入参层，命中后改写为
  安全占位继续放行）刻意不同：一句已被识别为注入攻击的用户输入没有"安全改写后继续对话"的合理中间态，
  故为硬拦截而非改写。默认关闭；fail-open（围栏自身异常不打断正常对话）；指标
  `customerwork.prompt.guard.blocked`；config-driven 正则列表（`hooks.prompt-guard.injection-patterns`，
  与 `ToolGuard.destructivePatterns` 同一预编译 + 不区分大小写模式）。新增
  `PromptInjectionGuardMiddlewareTest`（5，覆盖 10 种默认模式的正/反例）。
- **消息级用户反馈闭环（点赞/点踩）**：`ChatResponse` 新增 `messageId` 字段（`/chat` 单一调用点新增，
  低风险），`POST /api/customer/feedback` 提交反馈、同 `messageId` 重复提交按最新覆盖（用户改主意允许
  更正）。`DOWN` 类型与 `QualityFeedbackRecorder`（系统主动质检）同一模式沉淀到 `FactLog`——是数据飞轮
  除系统主动质检外的**另一条用户主动输入通道**。`MessageFeedback`/`FeedbackStore`/`InMemoryFeedbackStore`/
  `JdbcFeedbackStore`（`cw_message_feedback` 表）/`FeedbackConfig`/`FeedbackService` 是第 5 次套用本仓库
  已验证的 Store SPI 模式。`FeedbackService` 复用 `TenantResolver`（第 3 处调用点）。反馈本身建模为不可变
  事实快照（record），非状态机实体——没有合理的"待确认反馈"这种中间态。**诚实边界**：只做"记录"，筛选
  回流是离线人工职责（同 `QualityFeedbackRecorder`）；V1 仅覆盖非流式 `/chat`，`/chat/stream` 逐 token
  输出没有单一终态对象可挂 `messageId`，需注入协议层事件才能覆盖，属后续扩展点。
  新增 `FeedbackStoreTest`（5）、`FeedbackServiceTest`（5）、`FeedbackConfigTest`（2）、
  `JdbcFeedbackStoreTest`（3，MySQL 门控）。
- 文档：README 特性表/API 表/闭环说明新增两条目；`docs/生产接口使用手册.md` 新增 §3.8"消息级反馈"、
  §9 补入站防注入围栏行为说明、接口总表增至 27 个端点；`docs/部署手册.md` 表数从 6 张更新为 7 张、
  JDBC store 从 4 个更新为 5 个；`mysql/schema.sql` 补 `cw_message_feedback` DDL；prod yml 新增
  `feedback.store-mode: jdbc`。

### SLA 升级引擎 + 业务数据分析聚合（P7）

在工单系统落地的基础上补齐运营视角：工单卡了多久要告警、这段时间业务运转得怎么样。

- **`HandoffSlaScheduler`（SLA 升级引擎）**：周期扫描 `PENDING`（超 `human-handoff.sla-pending-seconds`
  无人接单）与 `CLAIMED`（超 `human-handoff.sla-claimed-seconds` 未结案）两阶段超标工单，结构化告警
  日志 + 指标 `customerwork.handoff.sla.breach`（tag `stage=pending|claimed`）。与
  `ApprovalTimeoutScheduler` 的 "escalate" 分支同一设计语言——只读扫描 + 告警，不引入新状态、不做
  自动流转（人机切换没有"自动接单/自动结案"这种合理兜底）；每周期对仍超标工单重复告警，依赖下游
  日志/指标系统去重聚合（与既有 `ApprovalTimeoutScheduler` escalate 分支一致，不新增去重状态）。
  两阈值默认 0（禁用）。新增 `HandoffSlaSchedulerTest`（4）。
- **`FactLog.readRecords`（纯增量扩展）**：新增 `FactRecord(ts, tenant, fact)` + 按租户读取带时间戳的
  事实记录，供按窗口聚合统计；不改动既有 `read()` 方法与其调用方，零回归风险。
- **`BusinessAnalyticsService` + `GET /api/customer/analytics/business`**：一次性聚合审批 / 人机切换 /
  质检三个业务维度的窗口内统计（`ApprovalStats`/`HandoffStats`/`QualityStats`，均为纯数据 record，
  区别于有状态机行为的 `ApprovalRequest`/`HandoffTicket` 充血模型）——审批放行率与平均决策时长、
  人机切换平均接单/结案时长、质检失败数与均分，均附带**不受时间窗影响的当前积压快照**
  （`currentPendingBacklog` 等）。审批/人机切换维度在应用层内存过滤（两表体量小，属可控案例数
  而非海量流水），未新增按时间范围查询的 SQL 方法。质检维度按租户查询 `FactLog`（无法跨租户汇总）
  且用 try/catch 安全跳过非质检失败的事实（`FactLog` 还被 `InMemoryLongTermMemory` 写入纯文本长期
  记忆事实，双写入源已验证）。**诚实边界**：不提供"转人工率"——没有可靠的"时间窗内会话总量"数据源
  （已结束会话已从状态存储删除，无法反推历史开启量），不编造不可靠的分母。
  新增 `BusinessAnalyticsServiceTest`（5：放行率/平均决策时长/积压快照、接单结案时长、质检均分与
  非质检事实安全跳过）。
- 文档：README 特性表/API 表/闭环说明新增两条目；`docs/生产接口使用手册.md` 新增 §8.6"业务数据分析
  报表"、接口总表增至 24 个端点。

### 工单系统 + 人机切换闭环（P6）

补齐"AI→人工转接"此前只打日志、生成不落库随机字符串的空实现——转人工升级为可查询、可流转的工单闭环。

- **`HandoffTicket`（充血领域模型）**：状态机 `PENDING`（待坐席接单）→（claim）`CLAIMED`（处理中）→
  （resolve）`RESOLVED`（结案，会话可回收给 AI 续接），`claim`/`resolve` 各自前置状态校验、重复
  流转 fast-fail（`IllegalStateException`）；包级 `reconstruct()` 静态工厂供存储层重建跳过状态机校验。
- **`HandoffStore` SPI + `InMemoryHandoffStore` + `JdbcHandoffStore`**：与 `ApprovalStore` 同一模式——
  默认进程内实现离线可测，`human-handoff.store-mode=jdbc` 落 `cw_handoff_ticket` 表（`HandoffConfig`
  按 `@ConditionalOnMissingBean` 装配，复用 `session.mysql.*` 连接配置构建独立连接池）；下游声明同类型
  Bean 即可整体覆盖（如 Redis 实现）。JDBC 实现 `catch(Exception)` 而非 `catch(SQLException)`（同
  既有 JDBC store 约定：HikariPool 连接失败抛非受检异常）。
- **`HandoffService`**：create（AI 转出登记 PENDING）/ claim（坐席接单）/ resolve（结案回收）/
  list / listByStatus / find，单一防御点 `require()` 保证工单必须存在。
- **`HumanHandoffTools.transferToHuman` 重写**：注入 `HandoffService` 后真实登记工单（未注入退化为
  纯文案兜底，保持工具向后兼容）。**已知限制（如实记录，非本次范围）**：框架工具调用未打通
  RuntimeContext 注入，本工具与 `AfterSalesTools#submitRefund` 同样拿不到真实 sessionId，沿用同一
  占位值 `"agent-tool"`——工单暂无法精确关联发起会话。
- **`HandoffController`**：`GET /api/customer/handoffs`（按 status 过滤）、`GET /{id}`、
  `POST /{id}/claim`、`POST /{id}/resolve`；领域异常转 404/409；决策写入 `cw_audit_log` 审计留痕。
  与资金类 `ApprovalController` 不同，接单/结案不产生资金动作，未接入同等强度的操作员令牌鉴权。
- **`TenantResolver` 式的既有约定复用**：`ToolRegistrar` 新增 `HandoffService` 构造参数，3 处调用点
  （2 个测试 + `customer-web` `CustomerWebAgentConfig`）同步更新。
- **文档**：README 特性表/API 表/闭环说明新增人机切换条目；`docs/生产接口使用手册.md` 新增 §6
  "人机切换接口"（后续章节整体重新编号 §6→§10）；`docs/部署手册.md` 表数从 5 张更新为 6 张、
  JDBC store 从 3 个更新为 4 个；`mysql/schema.sql` 补 `cw_handoff_ticket` DDL；prod yml 新增
  `human-handoff.store-mode: jdbc`。
- 新增 `HandoffStoreTest`（13：状态机 + fast-fail + 委托存储）、`HandoffConfigTest`（2）、
  `JdbcHandoffStoreTest`（3，MySQL 门控）、`HumanHandoffToolsTest` 扩展（2：兜底 + 真实登记）。
  本轮全仓测试 starter 312 → 331（+19），全仓 BUILD SUCCESS，0 failures。

### 生产级线上故障定位（P5 — 可观测性纵深）

补齐"从用户报障到定位根因"链路上此前半实现/未实现的环节。

#### P0 — 全链路日志关联（requestId / sessionId 打通）
- **MDC 日志贯穿（Reactor Context → SLF4J MDC 桥接）**：此前 `RequestIdWebFilter` 已把 requestId 放进
  Reactor Context、logback pattern 也已写 `%X{requestId}`，但 WebFlux 反应式栈跨线程流转，ThreadLocal 的
  MDC 拿不到 Context 里的值——`%X{requestId}` 永远是空，日志无法按请求聚合（"断头路"）。新增
  `MdcContextLifter`（`CoreSubscriber` 实现，每个信号回调前把 Context 的 requestId/sessionId 同步到 MDC，
  Context 无该键时从 MDC 清除防线程复用串号）+ `MdcConfig`（`Hooks.onEachOperator` 全局织入，
  `observability.mdc-enabled` 默认开）。sessionId 由 `CustomerServiceController` 的 chat/stream/intent/agui
  经 `contextWrite` 注入。logback pattern 增加 `%X{sessionId}`。`RequestIdWebFilter.CONTEXT_KEY` 复用
  `MdcContextLifter.REQUEST_ID_KEY` 常量避免两处漂移。新增 `MdcContextLifterTest`（5 例）。
- **错误响应体带 requestId**：`GlobalExceptionHandler` 各处理方法接收 `ServerWebExchange`，从
  `X-Request-Id` 响应头取值放入错误体 `requestId` 字段（无则省略），用户报障可直接自助提供定位凭据。
  未处理异常日志改为占位符错误码 `UNHANDLED_ERROR`。新增 `GlobalExceptionHandlerTest`（3 例）。
- 修正 `docs/生产接口使用手册.md` §1.3/§9：明确 MDC 自动关联与错误体 requestId 的落地事实。

#### P0/P1 — 会话诊断聚合 API + 审计/FactLog 查询打通
- **会话故障诊断全景（`DiagnosticService` + `GET /api/customer/diagnostics/session/{sessionId}`）**：
  把此前散落在 StateStore / 对话阶段 / 槽位 / 审批 / 审计 / 质检六处、需人肉查 4 张表 + grep 日志才能
  拼出的会话现场，聚合为单个只读 `SessionDiagnostic`。**每个数据源独立 try/catch**——单源不可用
  （MySQL 瞬断 / 审计后端未接入）只标注到 `degradedSources` 并降级，绝不让诊断工具自身崩溃
  （它恰恰要在系统部分故障时还能用）。读取涉及 JDBC/文件 IO，统一在 `boundedElastic` 执行不阻塞事件循环。
- **审计查询 SPI（`AuditQuery` + `AuditRecord`）**：与写入侧 `AuditSink` 做接口隔离，仅可检索的
  `JdbcAuditSink` 实现（按 `agent_name=CustomerServiceAgent-<sessionId>` 后缀 LIKE 匹配、ESCAPE 转义
  通配符、时间倒序）；默认 `LoggingAuditSink` 不实现，诊断链路以 `ObjectProvider<AuditQuery>` 可选注入、
  缺失时优雅降级。审计事件下钻端点 `.../audit?limit=`。
- **FactLog 按会话过滤**：诊断聚合读取租户事实流水并按 sessionId 过滤，取最近 N 条。
- **`SlotFillingService.peek`**：新增只读窥视收集进度（不推进/不创建状态），供诊断读取。
- **`TenantResolver` 抽取（DRY 收敛）**：把此前在 `CustomerServiceAgentFactory`、`QualityFeedbackRecorder`
  各复制一份的租户解析逻辑收敛为唯一 `@Component`，二者与诊断链路统一调用（遵循"防御式编程只保留一处"约定）。
- 新增 `DiagnosticServiceTest`（3：多源聚合 / 审计后端可选 / 单源失败降级不崩溃）+
  `JdbcAuditSinkQueryTest`（2，MySQL 门控）；`docs/生产接口使用手册.md` 增 §7.5 诊断端点。

#### P1 — 慢请求/异常请求留证
- **慢请求留证（`LatencyMiddleware` 增强）**：端到端耗时超过 `hooks.latency.slow-request-threshold-ms`
  （默认 5000ms）的请求、或出错的请求，输出一条结构化慢请求日志（outcome/agent/耗时/错误）+ 计数指标
  `customerwork.agent.slow.requests`（按 outcome=SLOW/ERROR 打标签），借 MDC 自动带 requestId/sessionId，
  供事后按 requestId 复盘；工具调用序列已由审计日志承载，日志只留精简现场并指引用诊断 API 拉全景。
  用 `doOnEach` 在终止信号向下游传播前完成留证（而非 `doFinally` 事后异步执行），确保时序确定。
  新增 `LatencyMiddlewareSlowRequestTest`（4）。

#### P2 — 分布式追踪关联（W3C trace-context）
- **traceId ↔ 日志关联（`TraceContextWebFilter`，零外部依赖）**：解析上游传入的 W3C `traceparent` 头
  （`00-<32hex traceId>-<16hex spanId>-<flags>`），把 trace-id 放入 Reactor Context（键 traceId）经
  MDC 落到日志 + 回写 `X-Trace-Id` 响应头——本服务日志因此与上游 Jaeger/Tempo 链路共享同一 traceId，
  实现"外部分布式链路 ↔ 本服务日志"交叉关联。`observability.trace-correlation-enabled` 默认开。
  logback pattern 增 `%X{traceId:-}`。**诚实边界**：本项只做关联；进程内真正采集 span 并导出 OTLP/Jaeger
  需引入 OpenTelemetry SDK（当前构建未含、本地镜像不确定可用），属基础设施扩展点，可另行声明框架
  `Tracer` Bean 接入（见 `TracingConfig`）。新增 `TraceContextWebFilterTest`（4）。

#### P2 — 合成监控（主动探活）
- **合成监控（`SyntheticMonitor`）**：定时用固定探针会话打真实对话链路（`CustomerServiceService.chat`），
  校验能返回/非空/非兜底回复，结果发指标 `customerwork.synthetic.probe`（tag result=UP/DEGRADED/DOWN）
  + 结构化日志供告警，在用户上报前发现故障。探针用独立 sessionId、探测后清理状态。
  **默认关闭**（`synthetic-monitor.enabled=true` 才 `@ConditionalOnProperty` 装配）——每次探测真实调用模型
  产生费用。`CustomerServiceService.FALLBACK_REPLY` 提升为 public 供探针判定降级。prod yml 增 synthetic-monitor
  块（默认关、可环境变量开）。新增 `SyntheticMonitorTest`（4）。

### 生产加固与功能完善（P0-P3）

#### P0 — 生产关键项
- **审批工单持久化（ApprovalStore SPI）**：从 `PendingApprovalService` 抽取存储层为 `ApprovalStore` 接口 +
  `InMemoryApprovalStore` 默认实现（`@ConditionalOnMissingBean`），下游可声明 JDBC / Redis 实现覆盖默认，
  保证审批单重启不丢失——对涉及资金的退款审批至关重要。`approve`/`deny` 后调用 `store.update` 持久化状态变更。
  新增 `ApprovalConfig` 配置类、`ApprovalStoreTest`（10 例）。
- **会话级并发控制（sessionId 锁）**：`CustomerServiceService` 新增 `ConcurrentHashMap<String, ReentrantLock>` 
  会话级锁，同一 `sessionId` 的请求串行执行（`withSessionLock` / `withSessionLockFlux`），防止并发写 StateStore 
  导致状态覆盖。锁在 `boundedElastic` 上获取，不阻塞 Netty 事件循环线程。`endSession` 清理会话锁。
  新增 3 个并发控制单测（同会话串行 / 不同会话并行 / endSession 清理锁）。

#### P1 — 架构健壮性
- **SlotFillingService 持久化（SlotFillingStore SPI）**：从 `SlotFillingService` 抽取存储层为
  `SlotFillingStore` 接口 + `InMemorySlotFillingStore` 默认实现，`SlotFillingProgress` 从内部类提取为公共类。
  下游可声明 JDBC / Redis 实现保证退款表单多轮信息收集中途重启可恢复。新增 `SlotFillingStoreTest`（7 例）。
- **审批超时自动处理（ApprovalTimeoutScheduler）**：新增 `@Scheduled` 巡检器，周期性扫描 PENDING 审批单，
  超过 `human-approval.timeout-seconds` 未决策的按 `timeout-action` 处理——
  `escalate`（升级告警）或 `deny`（自动拒绝）。默认禁用（`timeout-seconds=0`）。
  新增 `ApprovalTimeoutSchedulerTest`（4 例）。
- **会话超时自动清理（SessionTimeoutScheduler）**：`CustomerServiceService` 新增会话活跃时间追踪
  （`sessionActivity` map），`SessionTimeoutScheduler` 周期性清理超过 `session.idle-timeout-minutes` 未活跃的
  会话（移除热 Agent 缓存 + 删除持久化状态 + 清理会话锁）。默认禁用（`idle-timeout-minutes=0`）。
  新增 `SessionTimeoutSchedulerTest`（3 例）。

#### P2 — 架构健壮性续
- **限流算法升级（滑动窗口）**：`RateLimitWebFilter` 新增滑动窗口算法支持（`security.rate-limit.algorithm=sliding-window`），
  用 `ArrayDeque` 记录每个请求的时间戳，过期出队，避免固定窗口边界突刺（2x 突刺问题）。
  原固定窗口算法保留为默认。新增 3 个滑动窗口单测。
- **模型成本熔断（ModelCostCircuitBreaker）**：新增 `@Component` 成本熔断器，按分钟 / 小时窗口追踪 token 消耗量，
  超限拒绝请求防刷量打爆成本。`tryConsume(int)` 原子检查 + 回滚，`isCircuitOpen()` 检查熔断状态。
  配置：`model.cost-control.enabled` / `max-tokens-per-minute` / `max-tokens-per-hour`。新增 9 个单测。
- **FactLog 文件轮转**：`FactLog` 新增文件大小检查与轮转——超过 `max-file-mb` 时归档为 `.1` / `.2`，
  超出 `max-archived-files` 的最旧文件自动删除。新增 3 个轮转单测。
- **Jacoco 覆盖率门槛**：starter POM 新增 `check-coverage` 执行（`verify` 阶段），
  行覆盖率 ≥ 50%、分支覆盖率 ≥ 40%，低于门槛构建失败。

#### P3 — 功能补全 + 工程质量
- **审计日志结构化存储（JdbcAuditSink）**：新增 JDBC 审计落地实现，把审计事件结构化写入 `cw_audit_log` 表
  （自动建表），支持按类型 / 时间 / Agent 维度查询。`mysql/schema.sql` 新增建表 DDL。
  下游声明 `DataSource` Bean + `JdbcAuditSink` Bean 即可覆盖默认 `LoggingAuditSink`。
- **LLM-as-Judge 回复质量评测（QualityEvalRunner）**：新增 `QualityEvalCase`/`QualityEvalReport`/`QualityEvalRunner`，
  用 LLM 对 Agent 回复进行质量打分（1-5 分），量化回复的相关性、准确性、完整性。
  与 `IntentEvalRunner`（离线确定性评测）互补。新增 5 个单测。
- **多 Agent 专家 Agent 缓存**：`MultiAgentOrchestrator.buildSpecialists()` 新增 double-check locking 缓存，
  首次构建后复用，避免每次 `consult` 重建 Agent（Agent 无状态可安全复用）。
  `clearSpecialistCache()` 支持热更新。
- **技能版本管理（SkillVersionManager）**：新增 `@Component` 技能版本管理器，从技能内容（Markdown）中
  解析版本号（`<!-- version: x.y.z -->` 或 `# version: x.y.z`），追踪当前加载版本，
  `checkUpdates()` 检测版本更新触发热重载。新增 7 个单测。

#### P4 — 生产就绪缺口收尾（安全 / 数据一致性 / 多实例 / 数据飞轮）
- **审批端点操作员身份鉴权**（`af10745`）：`ApprovalController.approve/deny` 的 `operator` 此前是客户端自报的
  query 参数（有默认值），任何持有通用 API Key 的调用方都能冒充任意坐席放行退款、且无审计留痕。新增
  `ApprovalAuthWebFilter`（只拦截 approve/deny 两个资金放行端点，按 `security.approval-auth.operators` 的
  token→操作员姓名映射解析身份）+ 决策成功后经 `AuditSink` 留痕。新增 7 个单测。
- **审批放行后下游执行失败补偿**（`3d6eb42`）：`PendingApprovalService.approve()` 此前裸调用
  `onApprove.get().accept(req)`（无 try/catch），下游回调（如实际打款）失败时异常直接抛出、无执行态记录，
  呈现"工单显示已放行、钱其实没动"且无法追踪/重试的静默不一致。新增 `ExecutionStatus`
  （`NOT_APPLICABLE`/`EXECUTED`/`EXECUTE_FAILED`）与 `ApprovalStatus` 分层，`retryExecutionFailures(maxAttempts)`
  巡检重试（`human-approval.max-execution-retry-attempts`，默认 3），`deny()` 回调同款异常隔离。新增 6 个单测。
- **补齐 `store-mode=jdbc` 死配置**（`7d9c2aa`）：`human-approval.store-mode` 配置项文档写着 memory\|jdbc 二选一，
  但 `ApprovalConfig` 无条件返回 `InMemoryApprovalStore`，jdbc 从未被实现；`SlotFillingStore` 则完全没有存储模式
  概念。新增 `JdbcApprovalStore`（写入 `cw_approval` 表）+ `slot-filling.store-mode` 配置 + `JdbcSlotFillingStore`
  （写入 `cw_slot_filling_progress` 表），均复用 `session.mysql.*` 连接配置。`ApprovalRequest` 新增包级
  `reconstruct()` 静态方法供存储层重建持久化状态。新增 10 个单测（含 assumeTrue(MySQL reachable) 门控的 JDBC
  往返测试）。
- **对话阶段状态机可插拔存储**（`59c89b4`）：`DialogStageService` 此前直接持有 `ConcurrentHashMap`（进程内），
  多实例部署下同一用户请求被负载均衡到不同实例会导致阶段状态"归零"回 `GREETING`。新增 `DialogStageStore` SPI
  + `InMemoryDialogStageStore` 默认 + `JdbcDialogStageStore`（写入 `cw_dialog_stage` 表）+ `dialog.store-mode`
  配置。新增 9 个单测（含"两个 store 实例共享同一 MySQL"的跨实例模拟测试）。
- **生产基线补新配置项 + 多实例部署注意事项**（`62982d2`）：`application-prod.yml` 补齐上述新能力对应配置
  （`security.approval-auth.enabled`、`human-approval/slot-filling/dialog.store-mode: jdbc`），
  `security.auth.enabled` 生产默认值翻转为 `true`（fail-safe），`management` 收敛暴露面为
  `health,prometheus` + `show-details: when-authorized`；新增 `customer-web/application-prod.yml`
  （此前完全没有，收敛 admin 控制台暴露面 + `write-token` 走环境变量）。新增"多实例部署注意事项"文档
  （`docs/生产就绪评估.md` + README）：如实说明 `security.rate-limit`/`model.cost-control`/
  `CustomerServiceService` 会话级锁仍是进程内实现，横向扩容会放大配额/锁失效；`MockComplaintBackend`
  需在生产替换为真实实现。新增 `ProdProfileConfigTest`（两个 web 模块各一份，用
  `YamlPropertySourceLoader` 离线校验 prod yml 语法与关键配置键，不激活 profile 真实启动）。
- **质检失败数据飞轮闭环**（`C2`）：新增 `QualityFeedbackRecorder`——质检不通过时把回复内容 + 扣分项
  作为事实写入 `FactLog`（按 sessionId 解析出的租户分文件追加），供离线复盘；`AgentAssistController` 的
  `/quality/inspect` 接入本类而非直接调用 `QualityInspectionService`。诚实边界：只做"记录"，"从事实流水
  筛选回流知识库/评测集"是离线人工或独立批处理任务的职责。新增 3 个单测。
- 本轮全仓测试 **202 → 309**（starter 290 + example 10 + downstream 1 + customer-web 8，0 failures，
  13 skipped 为 MySQL 门控集成测试）。
- **部署手册（docs/部署手册.md）**：操作向生产部署文档——部署架构与组件清单、基础设施准备、
  数据库初始化（5 张表 DDL 走 DBA 流程）、环境变量清单（必填/可选分级）、operators 秘密配置下发、
  Mock 实现替换核对、多实例注意事项、安全暴露面收口、可观测性告警、灰度上线流程、回滚预案、
  已知框架限制复查表、部署前最终核对单（13 项可勾选）。README §6.20 增加入口链接。
- **生产接口使用手册（docs/生产接口使用手册.md）**：面向接入方与坐席系统的对接文档——调用总则
  （X-API-Key 鉴权/429 限流/X-Request-Id 追踪/sessionId 租户二段式与并发约定）、17 个端点
  逐一给出 curl 请求响应示例、退款闭环双路径（对话内工具 / 多轮表单）与审批状态机字段语义
  （status + executionStatus 双字段判完成态）、SSE 客户端处理规则（message/done 事件、120s 空闲
  断流、代理缓冲）、对话内部逻辑接入方须知（快车道/阶段状态机/压缩/兜底）、错误码与故障排查表、
  新调用方上线自检清单（7 项）。所有接口签名/请求头/字段均从 Controller 与 DTO 代码逐一核对。

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
- **业务功能补全 · 主动服务（复用 Channel 推送）**：新增 `notification` 包（`NotificationChannel` 抽象 +
  `LoggingNotificationChannel` 默认 + `ProactiveNotificationService`）——订单状态通知 / 满意度回访；
  customer-web 提供 `FeishuNotificationChannel`（`@Primary`，复用飞书 webhook，未配置降级日志）覆盖默认。
  `ProactiveNotificationController`（`/api/customer/notify/order-status|survey`）。新增 3 个单测。
- **业务功能补全 · 坐席辅助 + 会话质检（借鉴 AliGo）**：`assist` 包（`AgentAssistService` 给坐席实时
  话术/知识/工具建议）+ `quality` 包（`QualityInspectionService` 规则质检：资金违规承诺判不通过、绝对化/禁语扣分）；
  `AgentAssistController`（`/api/customer/assist`、`/api/customer/quality/inspect`）。新增 6 个单测。
- **业务功能补全 · 售中**：`OrderBackend`（`default` 方法演进，不破坏 Acme/Custom 实现）+ `OrderTools` 扩
  `modifyAddress`(改址)/`cancelOrder`(取消)/`urgeShipment`(催发货)；`MockOrderBackend` 提供演示实现。
- **业务功能补全 · 会员/账户**：新增 `MemberBackend`/`MockMemberBackend`/`MemberTools` + `member` 工具组——
  `queryPoints`/`queryMemberLevel`/`resolveAccountIssue`。新增 `MemberToolsTest`。
- **业务功能补全 · 投诉工单**：新增 `ComplaintBackend`/`MockComplaintBackend`(内存工单)/`ComplaintTools`
  + `complaint` 工具组——`fileComplaint`(建单)/`queryComplaint`(查单)，投诉从"仅识别意图"升级为"可建单+可跟踪"。
  新增 `ComplaintToolsTest`。Tool Group 5→7，`ToolRegistrar` 构造增 Member/Complaint Backend（修齐 3 处调用点）。
- **业务功能补全 · 售前导购**：新增 `ProductBackend`/`MockProductBackend`/`ProductTools` + `presale` 工具组——
  商品咨询 `queryProduct`、推荐 `recommendProducts`、库存 `checkStock`、优惠 `queryPromotions`；
  `IntentResult` 意图加 `presale`。补全客服旅程"售前"半段（关联下单转化）。新增 `ProductToolsTest`（4 例）。
- **业务功能补全 · 售后**：`AfterSalesBackend`/`AfterSalesTools` 扩 5 个工具——退款进度 `queryRefundProgress`、
  退货 `submitReturn`、换货 `submitExchange`、价保 `checkPriceProtection`、发票 `requestInvoice`；
  `AfterSalesToolsTest` 补 5 例。`ToolRegistrar` 工具组 4→5、构造增 `ProductBackend`（修齐 3 处调用点）。
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
- **生产加固（对照框架 open issues 的缓解措施）**：四项面向生产的加固——
  ①`HarnessAgentFactory` 在 `HarnessAgent.Builder` 上显式补默认 `generateOptions`，规避 **#1644**
  （未设 `generateOptions` 时 `streamEvents()` NPE）；
  ②`ToolGuardMiddleware.onActing` 增加**破坏性命令拦截**——对工具字符串入参匹配危险正则
  （默认覆盖 `rm -rf`、删除 `.agentscope/workspace`、Windows `del /f|/s`、`format `）命中即改写为安全占位
  `[BLOCKED_BY_TOOL_GUARD]` 并 `TOOL-GUARD-DESTRUCTIVE` 告警计数，缓解 **#1898/#1896**（沙箱可删 workspace / 跨用户写），
  正则经 `hooks.tool-guard.destructive-patterns` 可整体覆盖；
  ③`CustomerServiceService` 意图分类失败与 chat 兜底新增 Micrometer 计数 `customerwork.intent.classify.errors`、
  `customerwork.chat.fallback`（`ObjectProvider<MeterRegistry>` 可选注入、无则 no-op），缓解 **#1852/#1699**
  结构化输出静默失效；
  ④`chatStream()` 增加 **SSE 空闲超时**（`stream.idle-timeout-seconds`，默认 120，`<=0` 禁用）——空闲超时以
  `STREAM_IDLE_TIMEOUT` 友好收尾而非挂死，缓解 **#1741**（SSE 关不掉导致连接泄漏）。新增 9 个单测。
- **生产就绪评估文档 + 生产配置基线**：新增 [docs/生产就绪评估.md](docs/生产就绪评估.md)——对 agentscope-java（RC4）
  120 个 open issues 与本项目实际链路的交叉评估结论（已实测排除的风险 / 已加固缓解 / 架构规避 / 部署侧规避 /
  仍受框架限制需等待修复的项 / 版本升级策略）；新增
  [application-prod.yml](customer-work-example/src/main/resources/application-prod.yml) 生产配置基线
  （skill 仓库切 filesystem 规避 #1979/#1985、session 切 redis/mysql 规避 #1769、harness.subagent 生产关闭
  规避 #1954 系列、context 压缩阈值保守规避 #1740、stream 空闲超时规避 #1741、model.fallback 启用自研
  `FallbackChatModel` 规避 #1850），逐项配置均在注释中标注对应 issue 编号；README 6.13b 标注框架内置
  `fallbackModel` 已知缺陷（#1850），customer-web 操作文档 Channel 部分新增"生产边界"声明（#1966/#1619，
  多用户共享群存在上下文串扰风险，生产建议单租户群部署）。
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
  + `customer-work-downstream-app`（下游接入示例，包 `com.acme.support`，含接入契约测试）
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
