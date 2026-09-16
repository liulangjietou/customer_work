# 知识候选闭环（G9）

## 目标与现有边界

本工作包承接已批准的“缺口 → 可编辑候选 → 改进记录 → 准确版本评测 → 发布门禁”。候选编辑与恢复、不可变绑定、受治理的只读 ReAct 对照复评、逐题报告、正式 FAQ 发布/回执以及复评中断恢复均已接通，旧 /fill 直写入口已关闭。本地完整验收与真实模型隔离样本通过；PR 与远端 CI 状态以实际交付记录为准。知识已发布不等于线上效果已验证。

已核对的既有链路：

1. 原 KnowledgeGapController.fill → OpsAdminService.fillKnowledgeGap 直接插入客服库 cw_knowledge；当前旧入口明确拒绝并指向候选复评发布，不再写入正式知识。
2. KnowledgeGapReviewService 使用原始客服库信号及独立人工复核记录区分 KNOWLEDGE、REALTIME、DEPENDENCY、PROCESS、NON_BUSINESS 等处理方向。
3. ImprovementCaseService 已有责任认领、回归用例、制品绑定、复评与发布状态机，应复用，不再建立平行治理状态机。
4. EvalAdminService → EvalTriggerClient → 客服端 EvalService 运行当前生效配置。现有接口不能证明执行了编辑中的候选；仅保存候选指纹或把线上评测结果贴到候选上均不合格。
5. CustomerWorkConfigPublisher.previewVersionBinding 基于当前正式配置生成绑定，并不包含编辑中的 FAQ。KnowledgeBaseVersionService / KnowledgeProjectionService 管理的是受管知识库版本，与旧 FAQ 入口是不同数据路径，不能混同。

## 当前实现

新增候选 API 及两张 Admin 表（V108），候选主记录按租户与原始问题唯一，正文修订只插入、不覆盖。每次保存带客户端固定 UUID、预期候选修订和已核对的人工分类修订；操作人取当前登录态。请求不接收发布状态、评测结果或租户。

来源必须仍存在于当前租户，人工分类为 KNOWLEDGE 且修订匹配，才允许保存。规则建议或旧编辑器中的分类不构成依据。保存仅产生 DRAFT；不调用模型、FAQ 插入或发布任务。

两张表同一事务提交，行锁和修订比较防止并发覆盖；响应丢失后的相同提交回读已有版本，不额外制造审计修订。精确租户列显式使用 utf8mb4_bin，表默认仍遵循项目 utf8mb4_unicode_ci。

## 最初候选编辑阶段证据

- 真实 MySQL 与 Spring 事务：6 项，通过正式迁移验证旧修订保留、重复提交、大小写不同租户、来源/修订冲突、并发唯一获胜和失败回滚。
- 来源编排：9 项，通过非知识类别拒绝、规则建议拒绝、旧复核/缺失来源拒绝、源库故障透传、可信租户与操作人和不写 FAQ 的边界。
- 临时范围执行器：`/fyoung/tmp/customer-knowledge-candidate-next-tests`。使用独立输出目录和自有随机数据库；其 Java 依赖已复制为自有 baseline，与 G7 工作树解除依赖。
- 已完成接口权限/参数、真实 MySQL 与候选浏览器范围验收；尚未执行本工作包完整 Maven、前端完整门禁与远端 CI。本段为最初阶段证据；当前已纳入 G8，并已生成两库结构快照。

## 交付核对

- 实际双库提交裂缝恢复已通过：客服库先提交、后台回滚后，新 Worker 读取原回执完成同一意图，未重复插入 FAQ。
- 复评执行编号、截止时间与 Worker 过期恢复已补齐并通过范围门禁；评测完成仍不等同于发布或线上效果已验证。
- 真实模型构建、冻结 SQL 检索、ReAct 与 Judge 的隔离样本已验证，见最新记录；正式发布事务由独立双库集成测试验证，不能把两者合称线上全链路验收。
- 客服 V29 / Admin V108–V111 的迁移镜像与结构快照已补齐；继续执行冻结源码的完整 Maven、Admin/H5 门禁和远端 CI，交付 Ready PR。

## 编辑器与 HTTP 验证进展

看板已替换为候选编辑抽屉：并排核对来源与正文，支持恢复、保存结果未确认后的读取核对、修订冲突对照与明确保留本地编辑。沿用现有主题变量。类型检查及范围浏览器验收通过，详见后文；未把范围验收记为完整交付。

新增真实 Sa-Token/MockMvc 八项接口测试：匿名不可读写、保存必须同时具备查看与补知识权限、只读可恢复、单独写权限不可读、实际操作人来自登录态、非法正文与版本在入口拒绝。与真实 MySQL 六项、来源编排九项、排序规则五项合计 28 项通过、零跳过。首轮外部执行器未携带 Maven 使用的 `-parameters` 导致三项参数绑定失败；修正执行器后通过，未因此修改产品代码。

## 运行隔离必须处理的具体调用点

- `AdminAgentInstanceFactory.buildInnerReActAgent` 会清除正式 runtimeScope 的 MCP 授权缓存、注册工具分类，并使用持久状态库。仅换 sessionId 后调用该工厂，不能构成隔离的候选试用。
- `ExecutionModePolicy.PLAN` 经 `ExecutionModeMiddleware.planBlock` 改写写工具的字符串参数后继续调用工具，并非完全禁止调用，不能作为候选试用唯一的副作用隔离。
- `ModelExperimentArmEvaluationService` 已有真实模型、无工具请求和 `QualityEvalRunner` 评分的用例；候选评测可复用评分与模型构建能力，但必须明确标记所测范围，不能据无工具的回复评分宣称完整工具业务链通过。
- `EvalService.runQuality` 生成当前线上答复后才采集运行版本；新候选路径必须在执行前冻结待测配置，并在发布前重新核对。
- FAQ 正式表已有 tenant_id，既有 `KnowledgeDO` 不显式持有该字段，常规 Mapper 依赖租户拦截。候选发布和核对需逐条 SQL 显式携带受信租户，不能由 DTO 指定。实际召回是 `KnowledgeMapper.xml` 的 LIKE 三列匹配与 LIMIT 5，测试候选的检索不能替换为无条件拼入正文后假报线上召回成功。
- 正式 FAQ keyword 为 VARCHAR(255)、content 为 TEXT；候选前后端现已统一为 255 长度关键词、20,000 UTF-16 长度正文，候选表使用相同的 VARCHAR(255)/TEXT 类型。最坏 60,000 UTF-8 字节，中文和 emoji 边界已验证。

外部 Java 执行器已将 G7 的三个编译输出复制为自有 baseline（2,853 文件，baseline-manifest.json）；classpath 已不再引用 G7 工作树。最终验证仍需 G9 自身正式 Maven 产物。

## 前端范围验收

候选、来源复核与既有治理共 23 项浏览器通过（`customer-candidate-browser-green.log`）；候选覆盖 8 项，包含保存成功后的按钮状态、回执丢失先核对、读取故障留稿、版本冲突、只读、分类变化、相同 token 重新登录与迟到响应、两套主题窄屏关闭确认。

首轮 4 通过 2 失败：一项有效失败证明保存基准是普通变量，导致 dirty 缓存未更新，保存后仍可重复提交、无法进入治理；已改为响应式基准并按裁剪后的正文比较。另一项是夹具误要求统一加载状态组件展示原始错误文案，按组件实际契约修正测试，未修改产品错误处理。

窄屏截图首轮捕获了标签入场动画。新增标签可读宽度与滚动到来源顶部的验证后，Ember/Night 两项再次通过（`customer-candidate-visual-check.log`），截图显示完整来源与版本，无需修改主题或标签样式。纳入 G7 后类型和构建也已通过（`customer-candidate-build.exit=0`）。这些证据仍不代表候选评测/发布已完成。

来源服务故障恢复新增一项有效 RED：已保存候选因 Promise.all 中来源读取失败而无法显示。改为分别处理两项读取结果，来源不可用时继续展示已有正文，禁用修改及进入治理；来源恢复核对后恢复编辑。候选 9 项浏览器通过，类型检查通过；证据 `customer-candidate-source-read-red/green.log`、`customer-candidate-source-read-typecheck.exit`。源码变更后尚未运行完整前端/后端门禁。


## 复评目标与容量门禁（2026-09-14）

既有复评只检查失败列表不包含目标、版本匹配和回归指标，无法证明目标实际参加评测。新增有效 RED 在目标已遗漏时得到 READY_TO_PUBLISH。修复由 EvalDatasetAdminService 核验运行绑定的不可变快照，由 ImprovementCaseService 消费校验结果：类型、内容指纹、运行计数、失败 ID 唯一性与归属、目标存在性全部成立后才允许推进。读取存储失败继续保留实际错误，不能当成空集；不读取当前可编辑工作集来改写历史事实。快照自身负责验证内容指纹。复评和数据集范围 28 项通过，证据 `customer-knowledge-candidate-next-tests/target-proof-{red,scope}.log`。这只修复目标遗漏，不代表当前运行已能执行编辑中的候选。

容量有效 RED 证明 21,846 个中文字的草稿被 HTTP 接口接受，UTF-8 内容已超过正式 TEXT 上限。保存入口与编辑器统一限制正文 20,000 UTF-16 长度、关键词 255；既有正式 FAQ 不改字段。真实 MySQL 验证 20,000 中文为 60,000 字节、10,000 emoji 为 40,000 字节，完整读回；边界 HTTP 验证同时通过。候选 HTTP、来源、SQL 与排序规则 31 项通过（capacity-green.log）。

新增浏览器用例验证长来源完整保留、过长关键词明确提示精简、边界正文成功保存。当前候选 10 项浏览器通过（`customer-candidate-capacity-browser.log`）。G9 已纳入 G8 `c65b06c5`；正式 Maven 候选/评测范围 59 项通过；后续发布门禁修改后的评测/发布范围 31 项通过。前端类型检查与构建通过。最终 schema 快照与完整门禁仍待闭环实现完成后执行。


发布入口同时补齐历史证据复核：两个有效 RED 分别证明没有 evalRunId 的 PASSED 记录，以及目标未参评的历史 PASSED 记录，仍能创建发布任务。现在发布前加载所绑定运行，复用相同快照/目标/候选检查，拒绝无事实的状态标记。错误文案指向实际可操作的“重新绑定候选并复评”；不伪造发布成功或改变现有渠道确认流程。存储故障不入队的测试及有效发布正向对照通过。证据：`customer-knowledge-candidate-next-tests/publish-proof-red.log`、正式 Maven `customer-knowledge-publish-proof-final.log`（31 项，0 失败、0 错误、0 跳过）。


## 冻结检索与实际 Agent 试用（2026-09-14）

线上 FAQ 与 JSON_TABLE 快照查询现在共用 XML 的三列 LIKE、utf8mb4_unicode_ci 匹配、主键升序与前五条规则；唯一有意改变的线上行为是将此前未指定的顺序固定为主键升序。快照读取显式使用二进制租户条件；快照查询只读取传入的 JSON，不访问正式 FAQ 表。该单条快照查询关闭租户插件改写，因为其没有持久表或租户列，快照由受信租户服务生成。

SnapshotKnowledgeBackend 复用正式来源格式，记录实际召回的行号与检索异常。知识候选不会无条件拼入提示词；未命中或排在第六条之后的候选不构成召回证据。检索异常即使被工具框架转换为模型可见错误，评测结束仍会失败。

KnowledgeCandidateTrialRunner 实际构造 ReActAgent：每条用例独立 InMemoryAgentStateStore、独立会话与权限状态，只注册既有 KnowledgeBaseTools 和冻结检索后端；没有正式 MCP、工作区写工具或知识缺口统计。try-with-resources 关闭 Agent。模型主动调用工具之后才能获得候选正文，结果同时返回逐用例答复与实际召回候选的用例 ID。当前所测范围是只读知识问答，不将其记为完整 Agent 工具业务链的质量验收。

KnowledgeCandidateSnapshotService 核对当前候选修订和人工来源复核，冻结正式基线语料、加入候选后的语料及全内容指纹。原始行对象被修改也不会改变已冻结 JSON；发布前重新读取候选、来源与正式 FAQ，任意有效变化均拒绝沿用旧证据。

KnowledgeTrialModelService 通过 ModelConfigAccess 读取可运行部署，冻结模型地址、模型名、端点修订和上下文窗口。构建前核对输入，再解析 SecretRef；AdminModelFactory 新增接受已冻结窗口的重载，保持既有计费归因装配，构建过程不再读取一半的新配置。持久化字段不含密钥、SecretRef 或 Model 对象；模型停用、认证不可用、端点或窗口漂移都会阻断旧评测复用。

验证证据：

- `customer-knowledge-frozen-trial-scope.log`：正式 Maven 范围共 90 项，starter 21 项、Admin 69 项，0 失败、0 错误、0 跳过。
- `customer-knowledge-snapshot-plugin-final.log`：真实 MySQL 15 项通过，额外核对启用租户插件时正式查询与快照查询都可执行；中文/emoji 容量保持完整，测试自有数据库已删除。
- `customer-knowledge-trial-green.log`：实际 ReAct 框架 4 项通过；确定性脚本模型用于检查仅知识工具、会话隔离、无召回与错误分支。首轮 3 通过 1 失败是测试使用 Msg.toString 判断工具正文，已改为检查实际 ToolResultBlock 的 TextBlock；未因此修改产品逻辑。
- `customer-knowledge-snapshot-service-final.log`：候选与快照服务 16 项通过，包含正式基线和候选语料分别保留。
- `customer-knowledge-model-freeze-scope.log`：模型冻结、现有模型工厂及候选服务共 39 项通过。与上述 90 项存在重复回归，不直接相加作为唯一用例总数。
- `customer-knowledge-frozen-trial-progress-sha.json`：本轮结束的 35 份产品/测试文件指纹，仅是进度证据，不能代替最终门禁。

本节记录的冻结检索与试用组件已在下一节接入页面及改进记录；发布、真实模型质量评测与最终全量门禁仍未完成。

## 改进记录、对照报告与页面接通（2026-09-14）

调用链为 KnowledgeCandidateEvaluationPanel → ImprovementCaseController 的知识专用入口 → ImprovementCaseService 父记录状态机 → KnowledgeCandidateBindingService / KnowledgeCandidateEvaluationService → 实际 ReAct 知识工具和既有 QualityEvalRunner。父记录负责同库事务与状态推进；跨候选、模型、正式知识及审核集的只读冻结归属绑定服务；外部模型调用在事务外完成。

新增 Admin V109 的两张表：ai_knowledge_candidate_binding 以精确租户、父记录和完整制品指纹为键；ai_knowledge_candidate_evaluation 保存两组实际答复、评分和候选召回事实。正文为 LONGTEXT，原始评测 JSON 校验和在解析前验证；输入中的工具版本与 rubric 版本保存为不可变字段，不随后续程序升级重新计算历史版本。V109 镜像已同步，最终结构快照尚未导出。

绑定冻结候选修订、来源复核、正式 FAQ 基线、智能体提示词/轮数、显式答复与评分模型部署、APPROVED 的 QUALITY 数据集快照及目标用例。目标必须实际存在于该审核版本，不能只存在于当前工作集。两组评测只改变知识语料，保持其它维度一致；结果不进入客服端全局 QUALITY 运行基线。

通过条件同时检查：两组 Judge 完整、版本与用例计数一致、目标实际召回候选且评分通过、没有新增失败用例、整体评分不下降且达到通过线。评测期间输入变化会保留实际报告并标记复评失败。报告保存和父记录推进共用一个 Admin 事务；保存失败不能留下 READY_TO_PUBLISH。

接口：

- POST /api/improvement-cases/{id}/knowledge-candidate：同时要求 knowledge-gap:view、knowledge-gap:fill、improvement:manage，操作人由登录态确定。
- POST /api/improvement-cases/{id}/knowledge-candidate/reevaluate：同时要求 knowledge-gap:view、improvement:manage、eval:run。
- GET /api/improvement-cases/{id}/knowledge-candidate：同时要求 knowledge-gap:view、improvement:manage、eval:view，只返回候选身份、模型名称、用例和答复对照，不下发冻结整库、系统提示词或模型端点。
- 原运行配置复评/发布入口明确拒绝 KNOWLEDGE_CANDIDATE，防止评测了正式配置却把结果贴到知识候选。

页面从候选抽屉进入现有治理流程，选择智能体、答复模型、评分模型及已审核用例版本。版本选择改变后暂停旧候选复评；请求结果未确认时保留选择和备注，读取核对后再恢复操作。只读权限、关闭组件及同 token 重新登录均有验证。Ember/Night 样式继续使用原有主题变量。

本阶段验证：

- `customer-knowledge-review-api-scope.log/.exit`：正式 Maven 87 项，0 失败、0 错误、0 跳过。包含 5 项实际 MySQL 证据存储/事务回滚、真实 HTTP 组合权限、输入漂移、目标/回归判定和实际 ReAct 工具循环。脚本模型用于确定性检查，不作为真实 LLM 质量验收。
- `customer-knowledge-evaluation-browser-final.log/.exit`：候选、既有治理与新对照页面共 26 项通过；浏览器 API 为明确的测试夹具。
- `customer-knowledge-evaluation-build-final.log/.exit`：Vue 类型检查及生产构建通过。
- `customer-knowledge-evaluation-visual.log/.exit`：补充 2 项 Ember/Night、390px 长答复验证。截图在标签动画结束后采集，已人工检查；没有为截图改变产品主题或标签样式。

有效失败与修复：绑定请求响应丢失后重复提交曾清空已完成的评测，`customer-knowledge-binding-retry-red-valid.log` 复现为 completed-run 变 null；同一冻结版本现在只回读父记录。页面曾因监听返回新数组、父记录回读产生新对象而循环加载；首次浏览器失败已复现，改为分别监听标量身份，正常流程明确断言只有 3 次证据读取。浏览器另两次失败分别来自未限定下拉控件的选项定位、将搜索输入值误当已选标签，修正的是测试定位，没有修改业务逻辑。

以上都是本阶段范围证据，存在重复覆盖的测试数不累加。G9 尚未提交或创建 PR，发布流程与完整交付检查仍待完成。

## 2026-09-14：正式 FAQ 发布、回执与页面确认

实际调用链为 KnowledgeCandidateEvaluationPanel → 知识专用发布接口 → ImprovementCaseService 的父记录与候选冻结事务 → 既有 ImprovementAutomationWorker → KnowledgeCandidatePublicationService → 客服库 KnowledgePublicationStore。父改进记录同时保存固定任务号、评测运行与真实发起人，继续复用原状态机；没有新建平行发布队列。

客服 V29 新增租户发布锁与回执表。正式 FAQ、真实自增主键及回执在一个客服库事务提交；Admin V110 增加发起人和 PUBLISHED 状态。Admin 的父记录与候选冻结、解冻或完成在同库事务提交。数据库结果未知时保留 PUBLISHING 和原任务，Worker 先读对应回执再决定是否写入；确定的来源/版本/标题冲突才进入 PUBLISH_FAILED 并允许重新编辑复评。PUBLISHED 只表示正式知识已写入，效果保持 NOT_STARTED，页面明确说明线上效果尚未验证。

发布 SQL 显式精确匹配租户和来源修订，在 REPEATABLE_READ 事务中锁住正式语料范围后与实际评测基线比较。真实 MySQL 验证空库与已有知识库都能阻止核对窗口中的旧入口插入，避免只有新入口互斥而旧 badcase 写入仍穿过检查。其它租户消耗的自增编号不会被当成候选虚拟行号；回执保存真正 FAQ 编号。同任务不同意图及同候选修订的另一任务均拒绝重复发布。

页面先展示确切候选正文，再确认发布，并提交已审阅的候选指纹和评测运行。服务端持有父记录锁时比较这两个值，避免多人更新后旧页面发布了不同内容。发布核对期间自动读取，关闭面板或同令牌重新登录均停止旧轮询，迟到结果不能恢复旧界面。样式继续使用现有主题变量。

有效失败复现及修复：

- `customer-knowledge-publication-sql-red.log`：10 项中 1 项失败，正式标题重复被当成普通数据库异常；事务回滚后明确转换为确定的发布冲突。
- `customer-knowledge-publication-legacy-red-valid.log`：旧 /fill 返回成功并调用正式插入，修复后接口明确拒绝并且不触碰 FAQ。前一轮缺少模拟自增主键导致的响应断言异常不作为有效失败证据。
- `customer-knowledge-publication-confirmation-red.log`：候选指纹变化和评测运行变化两项均未被拒绝；加入锁内比较后通过。

最新终态退出码均为 0：

- `/fyoung/tmp/customer-knowledge-publication-sql-green.log`：客服发布真实 SQL 10 项、冻结检索 15 项、实际 ReAct 框架 4 项，共 29 项通过，零跳过。
- `/fyoung/tmp/customer-knowledge-publication-confirmed-admin.log`：Admin 101 项通过，零跳过；含发布编排、实际 HTTP 组合权限、已审阅版本、候选/父状态 SQL 事务与原有评测流程回归。
- `/fyoung/tmp/customer-knowledge-publication-confirmed-build.log`：Vue 类型检查与 Vite 构建通过。
- `/fyoung/tmp/customer-knowledge-publication-final-browser.log`：三个候选/治理文件共 29 项通过，含审阅正文、等待实际回执、响应丢失、读取故障、同令牌重登、Ember/Night 窄屏。首次新增轮询测试在复评读取完成前设置阻塞，测试等待点修正后完整范围通过，未据此改变产品行为。

已检查发布审阅和实际回执的页面截图。以上为组件、事务与范围回归证据，真实模型端点质量和本批完整项目门禁仍待完成；不得将当前范围通过记作 G9 已交付。


## 复评中断恢复范围验收（2026-09-14）

旧实现只记录 REEVALUATING，没有本次执行编号、截止时间或恢复扫描，既会永久运行，也无法拒绝旧请求的迟到结果。四项有效失败测试分别复现旧成功覆盖新执行、旧失败结束新执行、过期结果变成可发布、Worker 不结束过期执行。

- 父记录保存执行 UUID 与总截止时间，所有成功、失败回写在行锁内核对同一执行。过期任务由既有 Worker 结束为可重试失败；不自动重放模型调用。
- Admin V111 迁移把升级前缺少执行身份的运行项转成明确失败，保留其它事实，并约束新运行必须具备身份和截止时间。
- 同一个总时限约束基线、候选和 Judge 模型调用。实际 pending Flux 到期被取消，后续用例不能继续请求模型。
- 页面显示执行时限并轮询当前状态；未知响应保留输入，恢复为失败后由用户明确重新发起。关闭页面和登录身份变化停止轮询。
- 正式 Maven 范围共 103 项（starter 6、Admin 97），0 失败、0 错误、0 跳过；真实 MySQL 覆盖升级、租约与旧结果持久化隔离。证据：`/fyoung/tmp/customer-knowledge-reevaluation-recovery-final.log`，退出码 0。
- Admin 构建退出码 0，三文件浏览器测试 30 项通过。证据：`/fyoung/tmp/customer-knowledge-recovery-build.log`、`/fyoung/tmp/customer-knowledge-recovery-browser.log`。这些是范围验收，尚未替代完整项目门禁或外部真实模型验收。


## 精确租户读取与变更边界（2026-09-14）

原测试只证明开启插件时普通租户名称之间隔离，没有覆盖插件关闭或大小写不敏感的数据库比较。新增真实 MySQL 测试复现改进记录跨租户读取、同来源哈希选中其它租户记录、来源/复核理由越界，以及创建回归用例所用信号读取的大小写别名；模型装配入口另有四项归属失败测试。首次测试使用了不存在的枚举 DATA，属于测试编译错误；改成已有 DEPENDENCY 后取得有效行为失败，未据编译错误修改产品。

- 知识来源读取、分类 CAS、复核历史在存储层显式绑定可信租户、分区和哈希的二进制比较，不再依赖可关闭的租户插件。复核写入仍与审计同库原子提交。
- 改进记录按来源查询带精确租户条件，避免同哈希串记录；按 ID 读取核对真实归属，行锁 SQL 显式限定租户。创建与统计知识信号同样使用精确租户、分区和哈希。
- 试评模型在读取资产或解析凭据前只接受本租户或精确的 default 共享基线，保留通用模型入口的其它历史行为。
- 有效 RED：`/fyoung/tmp/customer-knowledge-boundary-red-valid.log`（12 项失败、0 错误）；补充原始信号/行锁 RED：`/fyoung/tmp/customer-knowledge-boundary-source-lock-red.log`（3 项失败、0 错误）。
- 范围 Maven：`/fyoung/tmp/customer-knowledge-boundary-green.log`，starter 10 + Admin 88 = 98 项，0 失败、0 错误、0 跳过，退出码 0。包括真实 SQL、原有分类并发/回滚、候选绑定与正式发布事务。尚未执行最终完整门禁。


## 迁移与镜像范围验收（2026-09-14）

V29 完整镜像和两种中断导入都曾因重复建表失败，三项实际 MySQL 失败测试已复现。当前识别两张发布表均完整导入时直接接管 V29；只有一张表时，幂等 CREATE 补齐另一张，保留原 FAQ。完整客服镜像已同步 V29，Admin V108–V111 与逐版镜像一致。

扩大门禁发现两张新表遗漏统一审计时间字段，现已补齐 created_at / updated_at。发布身份的八个列使用明确的二进制排序规则，测试逐列确认，防止租户或任务大小写别名；其它表和业务关联列仍遵守统一 utf8mb4_unicode_ci 约定。

`/fyoung/tmp/customer-knowledge-migration-complete.log` 退出码 0：starter 11 + Admin 8 = 19 项，0 失败、0 错误、0 跳过。覆盖新库、旧库、完整镜像、中断导入、实际跨库门面和四条建库路径的排序规则。两库结构快照已由自有临时库的正式迁移重新生成；快照写入模式完成，最终只读比对仍随完整门禁执行。客服快照为 52 张业务表，Admin 快照数量以导出文件为准。


## 真实双库发布恢复（2026-09-14）

`KnowledgePublicationRecoveryIntegrationTest` 使用自有 Admin/CW 数据库、真实候选/绑定/报告存储、真实 Spring 事务代理及 Worker。客服库已提交 FAQ/回执后故意回滚 Admin：后台父记录和候选共同保留 PUBLISHING，原任务编号不变。随后使来源漂移、租约过期，创建新服务与 Worker，依照已提交回执恢复 PUBLISHED/APPLIED，最终仅一条本租户 FAQ 和一条回执，默认租户种子保持不变；线上效果仍为 NOT_STARTED。

`/fyoung/tmp/customer-knowledge-cross-db-recovery-green.log` 1 项通过、0 跳过、退出码 0。首次编译夹具用了不存在的迁移构造重载；首次实际运行仅在结尾误把三个默认种子 FAQ 算作本租户重复发布，改为租户计数并同时核对全表增量后通过。这两次均未修改产品代码，不作为产品缺陷证据。实际评分输入使用脚本夹具，本测试证明两库事务与恢复边界，不是外部模型质量结果。


## 首轮完整门禁与治理补齐（2026-09-14）

Admin 构建、303 项单元测试、285 项浏览器测试通过；H5 构建、108 项单元测试、23 项浏览器测试通过。日志前缀为 `/fyoung/tmp/customer-knowledge-g9-`。后端 clean test 在 starter 的 2,143 项中出现 3 项架构约束失败，6 项条件跳过，后续模块没有执行，不能据此标记完整后端通过。

三个失败点为：试评未装配治理中间件、未使用项目统一 ManagedToolkit、配置版本与 FAQ 发布两个独立状态常量需要明确归属。随后两项实际行为失败测试复现输入防护和出站脱敏未生效。当前已接入平台已有基础治理 Bean、以 ManagedToolkit 管理工具生命周期，并将工具执行版本提升至 v2；新增工具返回隔离测试通过。独立状态机在常量门禁中明确说明，不强行耦合两个领域。

`/fyoung/tmp/customer-knowledge-governance-green.log` 32 项范围测试通过，0 失败、0 错误、0 跳过，含原三项门禁、实际防护行为及双库恢复。还须核对试评调用元数据/计量归类，然后重新执行完整后端门禁、真实模型验证与最终交付。治理装配修改后，前端源码与 285 项通过时保持一致。


## 离线计量与真实模型隔离验收（2026-09-14）

原范围测试关闭了调用日志，因而未发现答复试评默认记作 CHAT，且 Judge 直接请求模型未进入调用统计。新增实际框架测试先复现应有 8 条记录而实际仅 4 条；浏览器复现离线评测被显示成对话。有效 RED 为 `customer-knowledge-metering-red.log` 与 `customer-knowledge-metering-red-browser.log`。

现在答复与 Judge 均复用受治理的试评执行器。Judge 工具集为空；两组使用独立会话和各自冻结的九维版本，明确记为 EVALUATION，不写入线上实验曝光。异步回调显式传播可信租户。统计页增加“离线评测”显示和筛选，不为试评记录提供无效的工作区会话入口。工具执行版本提升至 v3，旧绑定不得混用新执行结果。

`/fyoung/tmp/customer-knowledge-metering-final.log` 退出码 0：starter 35 + Admin 11 = 46 项通过，0 失败、0 错误、0 跳过。验证实际模型/工具分段、Token、异步租户、两组冻结版本、唯一请求及基础安全治理。`/fyoung/tmp/customer-knowledge-metering-green2-browser.log` 新增统计交互 1 项通过。第一次修复验证中的 Java 重载与 Element Plus 选择器问题属于实现/测试修正，不替代先前有效 RED。

真实模型证据：`/fyoung/tmp/customer-knowledge-live-model3.json`，退出码 0。使用现有 qwen3.7-plus 部署，经 KnowledgeTrialModelService 读取可见部署、冻结版本、解析凭据并构建模型；实际 KnowledgeMapper 查询冻结 JSON，实际受治理 ReAct 与 QualityEvalRunner/Judge 执行。业务数据库使用只读连接，样本为虚构商店的开票与退货规则，没有发布知识。

- 基线：2 条用例，1 条通过；纸质票缺少相关知识，明确回复暂无规则；平均分 3.5。
- 候选：目标用例实际召回本次候选，2 条均通过；平均分 5，原退货用例保持通过。
- 答复生成 4 次、Judge 评分 4 次均记录 EVALUATION，租户归属一致，采集 9,846 Token，无线上实验曝光。
- 首次验收入口误用开发脚本的旧加密密钥，构建模型阶段因认证标签不匹配失败，未请求模型；改用当前应用配置后通过，没有修改或重置持久化凭据。

最终检查已冻结 103 个产品/测试/SQL 文件，清单 `/fyoung/tmp/customer-knowledge-g9-final-gate-sha.json`。当前 Admin 构建与 303 项单测通过；完整后端与 Admin 浏览器仍在运行。H5 产品/测试未变化，保留同批已通过的构建、108 单测与 23 浏览器证据。G9 仍未提交、未创建 PR。


## 最终后端通过与浏览器等待条件修正（2026-09-14）

完整 Maven `clean test` 退出码 0，共 4,624 项，0 失败、0 错误、7 项条件跳过：starter 2,146 / app-server 302 / channel 82 / Admin 2,093 / gateway 1。两库结构快照以只读模式核对通过。日志 `/fyoung/tmp/customer-knowledge-g9-final-backend-full.log`，模块与跳过清单 `/fyoung/tmp/customer-knowledge-g9-validation.json`。

Admin 首次最终浏览器检查为 285 通过、1 失败。失败轨迹显示来源抽屉关闭约 50ms 后，测试即对仍被关闭过渡遮挡的输入框执行 fill/Enter；关闭焦点返回入口时，回车重新打开了上一轮来源，第二条请求未发送。fill 不检查遮挡，不能把该操作当作用户已能进入下一轮。补充抽屉消失和入口焦点返回的等待，并明确断言第二个请求和第二条答复，再检查未知消息标识。产品代码未因此修改。

修正用例连续 5 次通过，日志 `/fyoung/tmp/customer-knowledge-source-focus-browser.log`、退出码 0。最终全套 Admin 浏览器再次执行；后端及已通过构建/单测的产品源码哈希未变化，无需重复后端门禁。最新产品/测试/SQL 清单为 `/fyoung/tmp/customer-knowledge-g9-final2-gate-sha.json`（104 个文件）。


## 本地最终验收（2026-09-14）

| 检查 | 结果 | 证据 |
|---|---|---|
| 完整后端 clean test | 4,624 项，0 失败、0 错误、7 条件跳过 | customer-knowledge-g9-final-backend-full.log / exit 0 |
| Admin 类型与构建 | 通过 | customer-knowledge-g9-final-admin-build.log / exit 0 |
| Admin 单元测试 | 303 通过 | customer-knowledge-g9-final-admin-unit.log / exit 0 |
| Admin 完整浏览器复跑 | 286 通过 | customer-knowledge-g9-final2-admin-e2e.log / exit 0 |
| H5 构建、单测、浏览器 | 构建通过，108 单测、23 浏览器通过；源码与此前同批门禁一致 | customer-knowledge-g9-h5-* |
| 真实模型隔离样本 | 目标召回、候选 2/2 通过、8 条离线计量 | customer-knowledge-live-model3.json / exit 0 |
| 凭据与差异检查 | 通过 | verify-no-committed-secrets.sh、git diff --check |

日志均保留在 /fyoung/tmp。最终 104 个产品/测试/SQL 文件哈希一致；后端通过后仅补充了一项浏览器用例的关闭等待和正向断言，后端、构建与单测涉及源码均未变化。已查看发布前正文确认和正式 FAQ 回执截图。七项跳过为百炼、四项 Nacos、Paddle OCR 与外部 RAG 集成的环境条件；不记成执行通过。提交使用个人身份，远端四项 CI 继续作为交付检查。

## 远端并发受理失败与修复（2026-09-15）

PR #217 首次提交 `26b2fb30` 的远端后端门禁捕获真实 MySQL 死锁：六个相同消息同时插入受理记录时，`WorkspaceMessageAcceptanceIntegrationTest.simultaneousDuplicateRequestsHaveOneExecutionWinner` 出现 `CannotAcquireLockException`。原测试只运行一次竞争，没有稳定覆盖被数据库回滚的受理事务。本地通过不能替代远端门禁结果。

调用链为 ChatController / VibeCodingController → WorkspaceMessageAcceptanceService → WorkspaceMessageReceiptStore 独立事务 → 模型和工具流。受理服务现在只对锁获取失败做最多三次重新受理，每次都在前一 Store 事务完成回滚后开始，沿用同一身份、指纹和时间。并发方已经受理时只返回原回执；连接故障和提交结果未知不重试，业务流始终位于重试范围外。唯一键竞争可能产生死锁的依据见 [MySQL InnoDB 锁说明](https://dev.mysql.com/doc/refman/8.0/en/innodb-locks-set.html)。

有效 RED：`customer-receipt-deadlock-red.log`，22 项中 1 失败、3 错误，退出码 1。除了可控的锁异常分支，还在真实 MySQL 插入之后注入回滚，验证重试必须跨越事务边界。修复后 `customer-receipt-deadlock-green2.log` 39 项通过，0 跳过；包含十轮六请求并发、回滚后唯一执行、竞争方胜出、三次耗尽、连接故障不重试和 HTTP 回执归属。

完整后端门禁 `customer-knowledge-g9-ci-fix-backend-full.log` 退出码 0：4,638 项，0 失败、0 错误、7 条件跳过（starter 2,146 / app 302 / channel 82 / Admin 2,107 / gateway 1）。三处后端产品/测试文件冻结哈希一致，两库结构快照只读比对通过。

远端 Admin 首轮为 284 通过、2 失败，两项均为候选抽屉的固定宽度断言：Linux 缺少中文字库，截图显示方框，标签宽度 52px 未达到测试写死的 60px。现在 CI 两端安装 Noto CJK 并检查中文字库可用；标签测试改为判断入场动画结束且完整文字位于实际标签内。Ember/Night 窄屏各重复三次，`customer-candidate-font-portability.log` 6 项通过，退出码 0；工作流 YAML 语法检查通过。

两端前端产品源码未变化，保留本批最终构建、303/108 单测及 286/23 浏览器通过记录；这次两处断言修正以六次范围回归和新提交的完整远端检查核对，未额外重跑无关模型验收。PR #217 仍为 OPEN/Ready，远端 CI 须在修复提交上重新核对，未合并。
