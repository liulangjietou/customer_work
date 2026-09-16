# 交接摘要基础契约与验证

基线为坐席工作台批次 `d1ed1557`，分支 `vibeCodingO/service-desk-assist`。本批先修正既有摘要与转人工增强链路，不新增数据库迁移，不把服务 API Key 交给浏览器。

## 调用链与职责

- 显式服务请求：`AgentAssistController.summary` → `ConversationSummaryService` → `ChatMessageStore`、已装配的 `Model`、`AgentAssistService`。
- 自动转人工增强：`HandoffService` → `HandoffCreatedEnricher.onHandoffCreated` → 独立工作线程中的摘要、`TicketClassifier`、`SeatRoutingScorer` → 原工单回写与审计。
- 摘要服务负责选取原文、生成方式、内容版本及缓存隔离；异步派发处负责捕获和恢复租户、配额主体。失败是否影响工单分配由增强器编排决定，不能由持久层把异常改成“空历史”。

## 当前行为

1. 缓存键包含当前租户与会话。`findLatest` 只校验历史并读取缓存，不调用模型；新消息或相同 ID 的内容修订会使缓存失效。历史读取失败向调用方报告，不能返回无历史或旧结果。
2. 从最新消息向前选取最多配置的历史条数，在 12000 字符预算内保留最近诉求。边界消息可截取末尾，来源中明确标记截断；短摘要也保持 Unicode 完整。
3. 响应新增 `evidence`，包含实际使用的消息 ID、角色、原文摘录、原消息时间、生成时间和内容版本。模型自报的来源字段被忽略。摘录证明对话中出现过相关内容，不证明退款、开票等业务已完成。
4. 模型错误或不守结构时，只返回原文与规则建议。规则不会承诺未经租户政策核实的退款资格、到账时间、开票时效或物流结果；建议回复改为可以人工审阅的客户话术。
5. 自动增强捕获发起时的租户和配额主体，在工作线程中恢复。摘要读取或生成失败仍继续工单分类与分配；应用销毁时释放组件拥有的线程池。

`assist.summary-enabled` 仍只控制既有自动预生成，默认关闭；既有服务凭证摘要接口保留显式生成语义。本批没有新增面向浏览器的模型生成入口，也没有将历史模型调用链声明为已接通 Admin 配额计费。

## 原有测试为什么漏报

原摘要测试只检查解析成功、降级标记和建议非空，未检查降级摘要是否掺入模型自由文本；缓存测试没有切租户或追加消息；转人工测试直接同步调用 `enrich`，避开了真正丢失上下文的线程边界。规则测试只看推荐工具名，没有核对其话术中的政策承诺。

## 失败证据

日志均在 `/fyoung/tmp`。

| 证据 | 修复前实际结果 |
|---|---|
| `customer-work-assist-before.log` | 17 条中 7 条失败：跨租户缓存、旧历史版本、自由文本混入规则、读取失败、最新诉求被截断、政策时效、工作线程上下文 |
| `customer-work-assist-routing-before.log` | 摘要历史失败后，工单分类仍应继续的用例失败 |
| `customer-work-assist-controller-before.log` | 将服务入口恢复为原 `fromCallable` 调用后，实际工作线程读取不到入口租户；2 条中 1 条失败 |
| `customer-work-assist-unicode-before.log` | 短摘要边界切断 emoji 代理对，UTF-8 编码检查失败 |

修复后增加原文证据、伪造来源忽略、同 ID 内容修订、读取失败、无租户拒绝、HTTP 5xx 及上下文回收断言。最终定向回归 34 条通过，日志为 `customer-work-assist-targeted-final.log`。模型使用 mock；线程传播在实际工作线程验证，未将同步替身作为异步证明。

## 完整门禁

JDK 17 Maven 全模块 `clean test` 已通过：3987 条，0 失败、0 错误、7 条环境门控跳过。模块计数为 starter 1936、app-server 173、channel 82、admin 1795、gateway 1；汇总保存在 `/fyoung/tmp/customer-work-assist-maven-summary.json`，完整日志为 `customer-work-assist-full-maven.log`。

真实 Redis 会话、Sa-Token Redis 持久化和 9 条消息 MySQL 集成测试均已执行。7 条跳过项为真实模型、4 条 Nacos、PaddleOCR 及真实知识检索服务；不将其声明为联调通过。源文件冻结清单为 `/fyoung/tmp/customer-work-assist-frozen-source.json`。

完整前端门禁通过，且在 Maven 完成后顺序执行：Admin 247 条单测、142 条 E2E，H5 53 条单测、6 条 E2E，两端类型检查和生产构建通过。日志依次为 `customer-work-assist-full-admin.log`、`customer-work-assist-admin-build.log`、`customer-work-assist-full-h5.log`、`customer-work-assist-h5-build.log`。测试期间源码未变，最终按冻结清单核对。

已纳入 Git 的凭据扫描、Compose 配置和差异空白检查通过，日志为 `customer-work-assist-secret-check.log`、`customer-work-assist-compose-check.log`。

## 后续接入

Admin 仍需通过工单 ID 解析合法会话及消息范围，复用 `user-ticket` 权限链；接手面板、建议采用到独立草稿、已发布知识依据，以及新增生成入口的配额与审计仍待实施。不能直接把当前按会话查询的服务接口开放给浏览器，不能将本批基础修复视为 DESK-01 完成。
