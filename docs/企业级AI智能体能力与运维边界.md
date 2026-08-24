# 企业级 AI 智能体能力与运维边界

> 本文仅描述当前代码中已有的控制面、运行面与审计事实，不把扩展点当成已上线能力。
> 默认关闭的功能仍需配置、外部依赖和生产参数后才会生效。

## 1. 能力全景

| 治理域 | 已有能力 | 权威事实 / 操作入口 |
|---|---|---|
| 身份与租户生命周期 | 用户 `authEpoch` 和租户 `accessEpoch`、后台会话撤销、租户状态/到期校验、渠道停用、访问快照可靠投递、既有 WebSocket 主动断开 | 非 `default` 业务租户的冻结、恢复、到期变更、手工撤销会话或退租都递增 epoch；快照经持久任务发布到独立 Nacos dataId，`GET /api/tenant/{id}/access-delivery` 查询最近交付状态；8080 在新请求和 WebSocket 握手时 fail-closed，并在应用冻结/退租快照后断开既有连接 |
| 长期记忆与隐私 | 主体级隔离、明示同意、查看、JSON 导出、撤回并删除、定期保留清理 | `/api/customer/user/privacy/memory-consent`、`/memory`、`/memory/export`；只有已验签 USER 才按用户跨会话共享；生产门禁强制 `provider=memory`、记忆与同意记录均为 JDBC |
| FinOps | token 实时配额（`BLOCK/DEGRADE/WARN`）、逐模型段冻结价结算、T+1 精确金额账单与源事实对账、线性预测、预算/预测超限告警、告警 ACK、CSV 导出 | `/api/billing/reconciliation` 按租户/日期/币种报告 `MATCHED/INCOMPLETE/STALE/MISMATCH`；缺价、缺用量和混合币种不伪造为 0；其余入口为 `/forecast`、`/alerts`、`/alerts/{id}/ack`、`/export` |
| EvalOps 与发布门禁 | 工作集 CRUD/导入导出、命名版本、审核与 diff；评测集不可变快照、内容指纹、九维制品绑定、指标/回归/关键用例门禁、Judge 故障策略、重评与紧急豁免；实验启动前双臂离线评测 | `/api/eval/datasets/**` 与 `/api/aiconfig/runtime-publish/gate/**`；实验必须绑定已审核 QUALITY 版本，control/treatment 均通过才可激活；豁免必须绑定 taskId 和候选内容 hash，记录操作人、原因与原判定 |
| Agent 改进闭环 | KnowledgeGap/badcase 统一责任认领、SLA、目标回归用例、精确候选版本、复评、可靠发布任务和上线效果观察 | `/api/improvement-cases/**`；发布任务全目标 `APPLIED` 后冻结 revision 与信号基线，多副本租约 Worker 按真实 revision 曝光和同类问题复发输出 `VERIFIED/INEFFECTIVE/INCONCLUSIVE` |
| ModelOps | 模型资产与部署分离、SecretRef 版本轮换、端点保存期出网校验、连续失败/恢复阈值、冷却、限时人工 override、持久健康事件、动态路由 overlay、上线认证、影响分析、不可变路由版本、冲突校验与 dry-run | `/aiconfig/model` 是统一入口；凭据响应只返回元数据；路由候选部署必须具有当前、未过期且与端点/凭据版本匹配的 PASSED 认证；`UNHEALTHY/RECOVERING` 不进入实际选路 |
| KnowledgeOps | PUSH 文档源、增量/删除/全量同步、checkpoint CAS、幂等运行、文档 lineage、ACL、新鲜度与质量门禁、KB/Skill 不可变版本 | `/api/aiconfig/knowledge-base/{id}/sources/**` 管理来源与同步事实；Agent 保存时冻结当前 KB/Skill versionId，后续资产编辑不会让既有 Agent 静默漂移 |
| Trace / Replay | 调用时冻结 traceId、runtime revision/hash、最终模型参数白名单与输入 hash、RAG/工具摘要、模型/提示词/Agent/知识/工具版本与实验曝光；支持安全重放差异 | `GET /api/agent-call-stats/{id}/replay-manifest` 只读，`POST .../{id}/replay` 默认 MOCK；不存在 LIVE 且外部调用数恒为 0，DRY_RUN 只允许显式开启的隔离部署；不保存凭据、RAG 正文或工具原始结果 |
| MCP 治理 | 列表不返回配置内容；编辑详情要求 `mcp:edit`，敏感字段与 headers 只返回占位符，更新时按相同 JSON 路径合并旧值；真正调用工具要求 `mcp:edit` 并记操作审计 | `test-connectivity` 与 `debug/tools` 是只读探测，使用 `mcp:view`；`debug/call` 可能改变下游数据，使用 `mcp:edit` |
| 运行时配置完整性与语义缓存 | 消费端重算 contentHash，并在解密前按可信 keyId 验证 HMAC-SHA256；请求捕获实际配置 generation，hash 变化时先阻断缓存读写并清理旧代际，再原子应用新配置 | 缺失、篡改、未知 key 或正文不一致均 REJECTED；旧在途请求的回写因 generation 不匹配被拒绝，校验、缓存清理或配置应用失败均保留旧配置 |
| 在线实验 | 不可变双臂定义、用户/会话稳定 SHA-256 分桶、曝光归因、真实调用日志指标、样本门槛、错误率/P95 护栏、到期与自动停止 | 控制面生命周期 `DRAFT/RUNNING/STOPPED/COMPLETED` 是 desired state；effective state 单列 `INACTIVE/ACTIVATING/ACTIVE/ACTIVATION_FAILED/DEACTIVATING/DEACTIVATION_FAILED`，由 ACTIVATE/DEACTIVATE 发布任务与 ACK 事实计算 |
| SLO / error budget | 按租户、Agent 或渠道配置可用性与延迟目标；多副本数据库租约周期评估短/长窗口，计算剩余错误预算与 burn rate；告警 OPEN/ACKED/RESOLVED、恢复事件与可靠通知 | `/api/slo/policies`、`/alerts`、`/alerts/{id}/ack`、`/alerts/{id}/events`；同一策略只允许一个活跃告警，`NO_DATA`/样本不足不误判恢复，事件与通知任务同事务提交后由租约 Worker 重试投递 |
| 业务结果—成本 | 按真实调用会话汇总技术成功、自动解决代理指标、转人工、token/金额完整度与 CSAT，并提供会话下钻和单位成本 | `/api/business-outcomes/summary` 和 `/sessions`；仅完整单币种事实计算单次自动解决代理成本，接口随返指标口径与 availability，不把技术成功冒充真实业务解决 |

## 2. 关键运维闭环

### 2.1 撤权与退租

以下流程针对可管理的非 `default` 业务租户；`default` 是保留租户，控制面不允许冻结或退租，运行时也不要求其访问快照。

1. 控制面更新租户状态或 epoch，并在同一事务中创建访问快照投递任务。
2. 事务提交后尝试注销该租户的 Sa-Token 后台会话；Redis 故障不回滚已提交的安全状态，后续请求仍由 epoch 校验拒绝。
3. 退租额外停用渠道绑定，保留租户主记录和业务数据，用于状态机与交付审计；当前不级联删除业务数据。
4. 持久 Worker 以租约、指数退避和新任务覆盖旧任务的方式投递快照；撤权任务默认不因暂时失败而永久放弃。
5. 8080 在请求热路径只读内存快照，缺失、过期、epoch 不匹配、冻结、到期或已退租均拒绝；应用冻结/退租快照后，连接注册表会主动断开该租户已有的用户与坐席 WebSocket。

控制面通过 `GET /api/tenant/{id}/access-delivery` 查看最近一次快照的步骤与投递状态；提交退租不等于所有运行时
已确认，运维应以该交付事实和运行时观测为准。

“实时撤权”的精确语义是：控制面当前实例的后台会话撤销立即执行；运行时每个新请求都对本地已应用快照
校验 epoch。跨运行时阻断由访问快照投递完成，是可观测的最终一致过程，不应宣称为零传播延迟。

### 2.2 长期记忆合规

- 已验签 `USER` 主体可跨会话共享自己的长期记忆；其它入口按完整 `SESSION` 隔离。`sessionId` 是客户端可控文本，不能仅凭 `u<userId>:conv-*` 外形当作身份。
- 生产 profile 开启 `memory.consent-required=true`；未同意时既不记录也不召回。撤回时先写撤回状态，再同步擦除 L2 记忆和 L3 事实。
- 撤回与并发写入竞争由写后再检查处理；如果记录过程中发生撤回，刚写入的数据会再擦除。
- 保留策略分批删除过期 L2/L3 数据和超期的“已撤回同意”记录；生产默认记忆 180 天、撤回证明 2555 天，以部署时配置为准。
- 百炼、Mem0、ReMe 适配器仍可用于非生产环境；当前外部 Provider 没有统一的导出、擦除和回执 SPI，
  `ProductionReadinessValidator` 因此在长期记忆开启时强制 `provider=memory`、`store-mode=jdbc` 和
  `consent-store-mode=jdbc`。这是 fail-closed 准入限制，
  不是放任第三方残留数据。

### 2.3 评测、模型与发布

- 每次评测在执行前固化实际用例序列和内容 hash，运行事实绑定评测集、模型、提示词、Agent、知识库、工具、Judge 和 rubric 版本。
- 发布 Worker 在 Nacos 投递前执行门禁。无匹配且完整的评测事实、关键用例失败、指标低于阈值、回归超标或 Judge 故障都可按策略阻断。
- 模型凭据轮换、端点修订或认证过期会让旧认证变为 `STALE/EXPIRED`；路由激活和实验启动不接受这类过期事实。
- 自动巡检按连续失败阈值把部署转为 `UNHEALTHY`，冷却结束后进入 `RECOVERING`，达到连续成功阈值才恢复；
  健康选择语义变化时，为全部引用 Agent 同事务创建 `HEALTH_OVERLAY` 可靠发布任务。普通 failover、策略候选和
  在线实验臂使用同一 overlay，实验不会因某臂故障把受试用户跨到另一实验臂，而是回到健康基线。
- `PUT /api/aiconfig/model/{id}/health-override` 只接受带原因和到期时间的限时覆盖，要求独立权限
  `model:health-override`；设置、清除、到期和探测状态转换均持久化事件。`FORCE_HEALTHY` 是有审计的运维判断，
  不会改写底层探测事实。
- 模型删除、停用或凭据轮换前可查看 Agent、渠道、路由策略与在线实验引用；实验已撤流并取得对应 APPLIED 事实后才不再作为阻断项。
- 模型 `baseUrl` 只允许 http/https，拒绝 userinfo、query 和 fragment；保存时解析全部地址，默认只放公网。
  `admin.model.egress.allowed-hosts` 非空后切换为严格 host 白名单，支持精确值和 `*.example.com`；名单命中只放宽
  RFC1918/IPv6 ULA，环回、链路本地（含 `169.254.169.254`）、未指定和组播地址仍永久拒绝。
- 连通性/健康探测及认证的连通性采样会在连接期重新解析 DNS，把当次校验通过的地址列表直接交给 OkHttp，
  并关闭 http/https 自动重定向。已有 SecretRef/旧密文的部署修改 `baseUrl` 时必须同时重新提交凭据，
  避免旧凭据被重定向到新端点。
- 配置详情对 SecretRef、密文、API Key、密码、credential、token、自定义 header 和实验分桶盐做结构化脱敏；
  历史模型 `baseUrl` 含 userInfo/query/fragment 或格式损坏时，模型列表/详情与配置版本页均整体替换为占位符；
  发布内部仍从权威表重建原始候选并在最终出网前校验。
- MCP 分页只返回非敏感摘要；编辑详情需 `mcp:edit`，敏感键和 headers 叶子值统一替换为
  `__MCP_SECRET_REDACTED__`。只有远程 endpoint 或 stdio 执行目标未变化时，占位符才能按相同 JSON 路径
  复用旧值；目标变化和新建必须重填全部凭据。远程 URL 仅允许 http/https，并拒绝 userInfo、query、fragment，
  凭据只能从 URL 移出到 headers（或完成迁移后的 SecretRef）；最终客户端与发布任务再次校验，配置版本详情
  对历史危险 URL 整段脱敏。
- 运行时消费者以 `RuntimeConfigContentHasher` 重算正文 SHA-256；算法排除 `publishedAt/revision/contentHash`
  投递元数据。缺 hash、非 64 位十六进制或与正文不一致均回传 REJECTED，且不清缓存、不切配置。
- 每个聊天请求在查缓存前捕获当前 `contentHash` generation，后续命中与写入都绑定该代际。配置切换期间暂停缓存
  读写并清理旧条目；应用成功提交新 generation，应用失败回滚旧 generation。切换前已开始的旧请求即使稍后完成，
  也会因 generation 不匹配而拒绝回写，不能污染新配置答案。

### 2.4 知识与 Skill 版本闭环

- 知识库和 Skill 的稳定记录只表示“当前编辑态”；每次创建或修改都会生成新的不可变版本。Agent 关联行保存
  `knowledgeBaseVersionId` / `skillVersionId`，因此资产继续编辑不会改变已经发布 Agent 的行为；要升级必须再次保存 Agent。
- PUSH 同步以 `requestId` 幂等，以 `expectedCheckpoint` 对当前 checkpoint 做 CAS。增量批次接受 `UPSERT/DELETE`；
  全量批次只接受 UPSERT，快照中缺失的旧文档会生成 DELETE 修订。冲突、向量化失败或质量不达标均不推进 checkpoint，
  也不发布新的知识库版本。
- 每个文档保留稳定 externalId、父修订链、来源版本、正文 hash、来源时间与 ACL。`RESTRICTED` ACL 按可信
  `subjectType + subjectId + channel` 的已配置维度共同匹配；未知 ACL 模式、损坏 ACL 或缺少可信主体均 fail-closed。
- 文档源记录最后成功同步时间、新鲜度 SLA、有效文档数、重复正文数和质量分。当前质量门槛按单个来源评估，
  分数取“去重率”和“期望文档覆盖率”的较小值；成功版本记录本次门禁分数和完整 KB 文档快照。
- 托管语料使用既有 `admin.knowledge.*` Embedding 配置，当前向量以 JSON 存 MySQL、应用层算余弦相似度；
  首个来源适配器仅为 PUSH。这是中小规模可执行基线，不代表已经接入企业专用向量库或外部抓取连接器。

### 2.5 KnowledgeGap / badcase 改进闭环

1. 运营在原页面认领信号，保存 owner 与未来 SLA；两类信号只共享治理状态机，原始问题事实仍留在客服库。
2. 绑定 Agent 时冻结当前运行候选的版本指纹和九维证据，并绑定一条真实存在的目标回归用例。
3. 复评在 Admin 长事务之外执行；只有运行完整、候选维度一致、目标用例通过、无新增回归且数据集未变化才可发布。
4. 发布前重新组装当前候选并拒绝漂移；状态推进与 `ai_runtime_publish_task` 入队位于同一 Admin 事务。
5. 数据库租约 Worker 等待任务全目标 `APPLIED`，再冻结 runtime revision、观察窗口、最小曝光量和复发基线。
6. 同类信号超过阈值立即记为 `INEFFECTIVE`；窗口结束且 revision 曝光达标才为 `VERIFIED`，流量不足为
   `INCONCLUSIVE`。这三个结论都保留观察计数，不以“已经发布”替代线上效果证据。

## 3. 上线前必须完成的环境配置

1. 启用 `admin.runtime-publish.nacos.enabled`，并使 admin 与 app-server 的 namespace、group、dataId 一致；
   多租户或租户专属实例必须显式配置 `customer-work.nacos.tenant-code`，不能依赖主 dataId 回落。
2. 配置相同的 admin 加密密钥与 app-server 解密密钥；密钥不得入库、入日志或出现在配置详情响应中。
3. MCP 敏感叶子必须迁入 SecretRef；数据库 config 只保留不可执行占位符，Nacos 只携带 `headersCipher`。
   数据库和 Nacos 仍必须启用最小权限 ACL、传输 TLS，并严格限制运行时 dataId 的写权限。
4. 生产开启 `customer-work.nacos.tenant-access-enabled=true`，设定快照主动回读间隔和最大陈旧时间。
5. 长期记忆使用 `provider=memory` + JDBC 存储，启用同意门禁与保留清理；隐私接口必须经用户 JWT 鉴权。
6. 为每个运行时实例配置唯一 `CW_RUNTIME_CONFIG_INSTANCE_ID`、至少 32 字节的独立 ACK token，并在
   `admin.runtime-publish.ack-identities` 中绑定租户与实例；新任务会冻结当时的完整目标集合，
   `minimum-ack-count` 仅供没有冻结集合的历史任务兼容。
7. 为需发布的租户配置 Eval gate，完成模型认证与基线评测；紧急豁免权限与普通评测编辑权限分离。
8. 企业私网模型通过 `admin.model.egress.allowed-hosts`（`ADMIN_MODEL_EGRESS_ALLOWED_HOSTS`）显式列出 host；
   启用运行时热配置时还必须设置消费者侧 `customer-work.model.egress.allowed-hosts`
   （`CUSTOMER_WORK_MODEL_EGRESS_ALLOWED_HOSTS`）。两个名单都要列全公网厂商与私网模型 host。
9. 需要持续模型探测时，在核算外部调用预算后开启 `admin.model-health.enabled`，并明确设置失败/恢复阈值、
   冷却、探测间隔和人工 override 最大时长；生产门禁要求运行时发布同时开启。默认关闭时仍可手工探测，
   但不会自动巡检和自动恢复。
10. 根据真实流量和风险定义 SLO 窗口、目标、延迟阈值和 burn-rate 阈值；确认 `admin.slo.automation.*`
    的评估/通知间隔、租约时长和批量大小适配实际流量，并为接收人授予 `slo:view`、为值班角色单独授予 `slo:ack`。
11. 应用 V91 迁移并校验 Agent 旧关联已回填不可变版本；为同步调用方单独授予
    `knowledge-base:source-sync`。上线前用重复 requestId、错误 expectedCheckpoint、质量失败和受限 ACL 四类用例验证 fail-closed。
12. 应用客服库 V21 与 Admin V96；按业务流量校准 `admin.improvement.automation.observation-window-ms`、
    `min-exposure-calls` 和 `max-recurrence-signals`，并向运营、评测和发布角色分别授予
    `improvement:manage`、`eval:run`、`agent:edit`。上线演练必须覆盖候选漂移、发布失败、信号复发和低流量四种结论。

## 4. 仍然存在的边界

| 边界 | 影响 | 当前运维建议 |
|---|---|---|
| 双臂离线评测依赖远程模型与 Judge | ACTIVATE 前会分别评测 control/treatment，但远程限流、超时或 Judge 故障都会阻断激活并产生真实费用 | 使用已审核的代表性 QUALITY 版本，核算评测预算；将失败事实与线上错误率/P95 护栏结合判断，不以单次离线分数替代长期观测 |
| 金额预测是简单线性外推 | 当前用“期内已用金额 / 已过天数 × 总天数”，没有季节性、节假日、增长趋势或置信区间 | 只作预算早期信号，不作财务承诺；对周期波动明显的租户使用外部预测平台 |
| 跨运行时退租依赖访问快照传播 | Nacos 投递、监听与主动回读存在延迟；在超过最大陈旧时间后新请求 fail-closed | 观测租户访问交付状态；快照发布失败时不要将退租宣称为已在全部运行时完成 |
| 外部长期记忆 Provider 尚未进入生产合规基线 | 百炼/Mem0/ReMe 没有统一的主体导出、擦除与可验证回执契约；生产门禁当前直接拒绝这类配置 | 开放前先补 Provider capability SPI、失败语义、回执审计和集成验证，再调整 `ProductionReadinessValidator` |
| AgentScope 厂商 SDK 推理出网尚未固定解析与重定向策略 | 保存期会校验端点，连通性/健康探测及认证的连通性阶段已固定 DNS 结果并禁重定向；但认证能力检查与实际推理仍由各厂商 SDK 管理 HTTP/DNS | 不把探测链路保障宣传成全部推理请求保障；在 SDK 支持注入受控 HTTP client 前，以网络 egress ACL/代理限制模型目的地并监控 DNS |
| SecretRef 当前只有本地 AES 材料后端 | 数据模型保留 Vault/AWS/Azure/GCP/ENV 类型，但服务只实现 `LOCAL_AES` 创建与轮换 | 不得宣称已接入外部密钥管理器；生产需求外部 KMS 时先实现对应 Provider |
| KnowledgeOps 首个来源适配器仅支持 PUSH，向量暂存 MySQL | 当前没有网页/对象存储/数据库 CDC 连接器；应用层向量排序不适合海量 chunk | 外部采集器按 checkpoint 协议调用 PUSH；规模增长前把检索实现替换为专用向量库，并保留版本、ACL 与 lineage 契约 |
| Replay 仅为只读清单 | 生产工具可能发消息、改数据或触发审批，查看权限不能隐式升级为执行权限 | 真正重跑必须进入隔离环境并逐项授权 |
| 单次自动解决成本仍是代理指标 | 金额已按模型段冻结价精确结算到 call/session，但“自动解决”当前定义为技术成功且无转人工，不等同于经客户确认的问题解决 | 结合 CSAT、复访或业务完成事件校准代理口径；缺价、缺用量和混合币种时保持 `PARTIAL/UNAVAILABLE/MULTI_CURRENCY`，不得强行比较 |
| 改进效果按精确问题哈希观察 | 能确定同一归一化问题是否复发，但同义改写不会自动归为一类；低流量窗口也无法证明候选有效 | 将 `INCONCLUSIVE` 保持为独立终态并人工延长/重开观察；需要语义复发聚类时先建立可审计阈值与误聚类评测，不直接改写现有精确计数 |

## 5. 验证口径

本文的“已有能力”指代码、数据迁移、接口契约与自动化测试已落地。
它不等于已完成真实 Nacos 往返、多 Pod 全实例 ACK、生产模型 Key 认证、
或已登录浏览器的端到端验证。这些环境依赖验证必须在对应部署环境中单独执行并留存证据。
