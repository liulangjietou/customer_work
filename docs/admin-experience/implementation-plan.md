# Admin 与客服体验升级实施验收计划

基线：85fde31c。批准依据：2026-09-10 的完整交互稿与五批方案；2026-09-11 确认开发。

## 完成标准

以真实产品和真实权限契约实现设计稿，不能把演示数据、空响应、未接通的按钮或仅通过页面挂载当作完成。范围包括当前 41 个静态后台入口、首页、动态工作区、登录/改密/异常页面，以及客户 H5 与坐席协作。

## 批次与要求

| 编号 | 要求 | 当前状态 | 必须核验的证据 |
|---|---|---|---|
| UI-01 | 六组导航、分组内菜单与历史、紧凑顶栏、页面层级 | 已实现，定向验证通过 | 当前权限菜单、收展/焦点/小屏、会话不卸载 |
| UI-02 | 全站配色、字体、尺寸、表格/表单/抽屉与各页布局 | 公共样式已落地，业务状态验收中 | 每个入口的有数据/空/失败/只读截图和操作 |
| UI-03 | 首页任务、智能体卡片、模型/MCP/知识库/评测等专用布局 | 智能体、模型、MCP 已改造；知识源/版本分区、知识库与评测失败恢复已实现；其余专用流程待完成 | 当前业务数据与跨页操作，按钮调用真实接口 |
| UI-04 | 图表随容器变化、可见性恢复与清理 | 已实现，定向验证通过 | 最小失败测试、真实 canvas/容器几何对齐 |
| CHAT-01 | 受理回执、同标识重试与对账、失败保留输入 | 客户 WS 入口和 H5 已实现，MySQL 与定向验证通过；坐席和工作区受理待接 | 落库失败/回执丢失/并发重复/内容冲突 |
| CHAT-02 | 权威终态、历史分轮、原文与运行指引分离 | 历史分轮、原文分离、实时终态与保存核对已实现并通过回归；完整持久对账待受理记录接通 | EOF 不伪完成，停止/错误/刷新/旧记录 |
| CHAT-03 | 双端重连补消息与工单快照 | H5 首连、重连、分页补拉和工单快照已实现；坐席端待实施 | WS/HTTP 对账，断线时消息及工单状态变化 |
| DESK-01 | 队列、会话、交接摘要和建议草稿一屏协作 | 待实施 | 接单/转接竞争，摘要权限，采用不自动发消息 |
| KB-01 | 授权来源预览、版本追溯与失效提示 | 待实施 | 引用版本、归属、跨租户/用户访问拒绝 |
| OPS-01 | 知识缺口分类、人工修正与已有改进闭环接通 | 待实施 | 非知识任务分流、候选/评测/发布门禁 |
| CONFIG-01 | 场景模板、草稿、试用、评测与上线检查 | 待实施 | 不隐式授权、不跳过发布门禁、保存契约完整 |
| H5-01 | 接收状态、来源、业务结果、转人工与输入区 | 受理状态、草稿与附件保留、中断片段查看已实现；来源和结果流程继续实施 | 360/390px、弱网、附件、焦点及软键盘 |
| QUALITY-01 | 类型/构建/完整测试/审查/真实浏览器 | 首批已通过，后续批次继续执行 | 当次完整日志，按要求完成租户和业务边界验证 |
| DELIVERY-01 | 个人身份提交、推送、Ready PR、CI、临时 worktree 清理 | 首批进入交付；各批状态以对应 PR 和远端 CI 为准 | commit/PR/CI 与主工作区保留证据 |

原生图片理解、语音、关闭页面后持久长任务为原方案可选项，不计入本次批准的首期范围。

## 设计约束

- 浅色基准：操作蓝 #3658CB、正文 #18273B、背景 #EFF3F8、完成绿 #267765、待确认 #925E13；映射现有主题体系，保留其他主题和自定义色。
- 单侧栏，六组生命周期导航；展开组内可滚动，所有分组入口可达。使用后端菜单名与权限，不制造业务路由。
- 正文 14–16px；标题 20–26px；固定主操作和清楚的状态反馈。主移动操作目标 44px。
- 宽屏坐席三栏，窄屏按流程切换；工作区结果、来源和执行信息分层。
- 所有输入和草稿保持租户、主体、会话隔离；所有技术细节按使用者权限呈现。

## 页面验收台账

状态“待验收”表示尚无产品级完成证明；通过设计稿检查不能勾选。

| 路由 | 页面源文件 | 状态 |
|---|---|---|
| `/system/user` | `@/views/system/UserManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/system/role` | `@/views/system/RoleManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/system/log` | `@/views/system/OperationLog.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/system/ai-audit` | `@/views/system/AiCodingAudit.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/system/agent-call-stats` | `@/views/system/AgentCallStats.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/system/menu` | `@/views/system/MenuManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/system/devtools` | `@/views/system/DevToolboxView.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/system/login-image` | `@/views/system/LoginImageManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/system/dict` | `@/views/system/DictManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/system/tenant` | `@/views/system/TenantManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/system/billing` | `@/views/system/BillingManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/system/config-version` | `@/views/system/ConfigVersionManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/system/slo` | `@/views/system/SloManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/aiconfig/model` | `@/views/aiconfig/ModelManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/aiconfig/mcp` | `@/views/aiconfig/McpManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/aiconfig/skill` | `@/views/aiconfig/SkillManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/aiconfig/agent` | `@/views/aiconfig/AgentManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/aiconfig/system-tool` | `@/views/aiconfig/SystemToolManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/aiconfig/knowledge-base` | `@/views/aiconfig/KnowledgeBaseManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/aiconfig/scheduled-task` | `@/views/aiconfig/ScheduledTaskManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/aiconfig/agent-task` | `@/views/aiconfig/AgentTaskManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/ops/eval` | `@/views/ops/EvalCenter.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/ops/badcase` | `@/views/ops/BadcaseReview.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/ops/semantic-cache` | `@/views/ops/SemanticCacheBoard.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/ops/prompt-version` | `@/views/ops/PromptVersionBoard.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/ops/csat` | `@/views/ops/CsatBoard.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/ops/knowledge-gap` | `@/views/ops/KnowledgeGapBoard.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/ops/dead-letter` | `@/views/ops/DeadLetterBoard.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/ops/business-outcome` | `@/views/ops/BusinessOutcomeBoard.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/contentguard/sensitive-word` | `@/views/contentguard/SensitiveWordManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/contentguard/rate-limit` | `@/views/contentguard/RateLimitRuleManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/contentguard/hit-log` | `@/views/contentguard/SensitiveHitLogBoard.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/contentguard/subject-quota` | `@/views/contentguard/SubjectQuotaManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/aiconfig/channel-robot` | `@/views/aiconfig/ChannelRobotManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/ticket/user-ticket` | `@/views/ticket/UserTicketManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/ticket/user-order` | `@/views/ticket/UserOrderManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/project` | `@/views/project/ProjectManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/sql/datasource` | `@/views/sql/SqlDatasourceManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/sql/define` | `@/views/sql/SqlDefineManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/workbench/site` | `@/views/workbench/WorkbenchSiteManage.vue` | 挂载及 390px 布局通过；业务态待验收 |
| `/workbench/sql-console` | `@/views/workbench/WorkbenchSqlConsole.vue` | 挂载及 390px 布局通过；业务态待验收 |

另验收：首页、工作区空态、动态工作区、SQL 动态报表、登录/注册/找回密码、强制改密、404 和客户 H5。

## 测试与交付

Bug 先用最小失败测试说明现有测试为何漏报。开发阶段按影响范围回归；提交前运行 JDK 17 Maven 全模块、Admin Vitest/E2E、H5 Vitest、两端类型与构建、静态与结构门禁。数据库验证使用隔离数据，迁移编号先核对共享库和活跃分支。

全量测试期间不改源码；不能将跳过、外部依赖不可用或模拟验证称为真实联调通过。完成完整审查后提交 Ready PR 并核对 CI；只有远端保留安全后清理本任务 worktree。


## 2026-09-11 实现与验证记录

- 三个 ECharts 组件改用容器观察与帧内合并；原有主题回归之外新增尺寸变化、重复通知、零尺寸和卸载清理验证。先确认三个组件的最小用例失败，再实现修复。
- 六组导航在当前分组内展示后端菜单；保留查询参数、动态智能体、移动焦点管理和稳定的会话历史挂载点。计数改为实际页面数量。切页保留草稿、导航收展、工作区产物宽度回归通过。
- 默认 Ocean 的正文、背景与操作色对齐设计稿；辅助文字采用 #5D6D83，使普通文字在背景和卡片上均达到 4.5 对比度。保留其余预设和自定义主题。
- 智能体提供卡片与列表视图，共用权限及操作；模型按部署、路由、实验分区；MCP 次要操作收进菜单，凭据状态使用中文。
- 工单、智能体和 MCP 的列表错误已复现并修复，复用公共分页加载状态；初次失败可重试，刷新失败保留上次结果，旧请求不能覆盖新查询。模型失败时不再把未知指标显示为零。
- 41 条业务路由已通过桌面挂载与 390px 文档宽度检查；这不代表所有业务操作、数据态和真实后端联调均已通过。
- 工作区新增原文元数据与历史阶段投影：普通聊天、编码和协作编码保持完整模型上下文，历史气泡展示明确记录的原始输入。仅附件消息保留；旧记录不做字符串截断，也不推测为最终结果。已用框架 AgentState JSON 往返和五组服务回归验证，79 条通过。
- 框架 Msg.getGenerateReason() 对缺失或非法字段会默认返回 MODEL_STOP；展示层改读实际保存的元数据。相同 turnId 的明确过程输出收纳到执行记录，UNKNOWN 旧回复保持独立，停止与等待状态单独标识。
- SSE 支持跨网络块的 CRLF 分隔，先检查 Content-Type；HTTP 200 的业务错误 JSON 不再伪装成成功空流。实时流的权威 done/停止/失败协议仍属于后续 CHAT-02，不能把本次传输修复等同于持久化完成保证。
- 评测切换类型时清空旧类型数据，失败可重试，未知指标展示为“—”；知识源抽屉按知识库和请求序号接收结果，切换后旧响应失效；文档源与版本记录分区展示。对应四条浏览器用例均已先失败、修复后通过。
- 当前阶段 Admin 单测 217/217、H5 单测 38/38、两端类型与构建通过；历史浏览器定向回归 8/8 通过。编码 API 的原文字段投影另有两条先失败后修复的请求契约用例。
- 首批最终门禁已完成：Maven 全模块 3892 条、0 失败、0 错误、7 条环境门控跳过；Admin 完整 125 条 E2E 通过，两端单测、类型与构建通过。真实管理员登录后核对了首页、智能体卡片/列表、模型部署、知识库及版本抽屉；390px 文档宽度为 390px。详细边界见 [首批验证记录](batch-1-validation.md)。
- 浏览器截图来自实际 Vue 产品组件与明确隔离的接口 fixture；它们验证布局与前端交互，不作为真实模型、数据库写入或跨租户后端联调证据。

首批独立交付包含公共布局、资产页面、失败恢复、图表尺寸和历史展示，不表示五批功能整体完成。后续继续执行 CHAT-01/02/03、DESK-01、KB-01、OPS-01、CONFIG-01、H5-01 和剩余页面的业务验收；提交、PR 与 CI 状态以各批远端记录为准。

## 消息可靠性进度

工作区实时终态已通过完整回归，范围见 [实时终态契约](chat-terminal-contract.md)，结果见 [终态验证记录](chat-terminal-validation.md)。此项覆盖普通聊天、编码、协作、诊断和重构。

客户 WS 受理与 H5 恢复已实现，细节见 [客户消息受理契约](message-receipts-contract.md)。包括同库事务与唯一键去重、回执查询、失败保留输入、首连和重连补拉、多页缺口恢复、明确显示查询失败、恢复查询不扣提问额度，以及异步帧按原租户发布。完整门禁已通过：Maven 3953 条，0 失败、0 错误、7 跳过；Admin 237 条单测与 133 条 E2E；H5 53 条单测与 6 条 E2E。验证边界见 [受理与恢复验证记录](message-receipts-validation.md)。

坐席与工作区受理、坐席重连及完整终态对账仍待继续，第二批整体尚未完成。
