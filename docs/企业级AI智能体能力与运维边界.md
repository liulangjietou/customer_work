# 企业级 AI 智能体能力与运维边界

> 本文仅描述当前代码中已有的控制面、运行面与审计事实，不把扩展点当成已上线能力。
> 默认关闭的功能仍需配置、外部依赖和生产参数后才会生效。

## 1. 能力全景

| 治理域 | 已有能力 | 权威事实 / 操作入口 |
|---|---|---|
| 身份与租户生命周期 | 用户 `authEpoch` 和租户 `accessEpoch`、后台会话撤销、租户状态/到期校验、渠道停用、访问快照可靠投递、既有 WebSocket 主动断开 | 非 `default` 业务租户的冻结、恢复、到期变更、手工撤销会话或退租都递增 epoch；快照经持久任务发布到独立 Nacos dataId，`GET /api/tenant/{id}/access-delivery` 查询最近交付状态；8080 在新请求和 WebSocket 握手时 fail-closed，并在应用冻结/退租快照后断开既有连接 |
| 长期记忆与隐私 | 主体级隔离、明示同意、查看、JSON 导出、撤回并删除、定期保留清理 | `/api/customer/user/privacy/memory-consent`、`/memory`、`/memory/export`；只有已验签 USER 才按用户跨会话共享；生产门禁强制 `provider=memory`、记忆与同意记录均为 JDBC |
| FinOps | token 实时配额（`BLOCK/DEGRADE/WARN`）、T+1 金额账单、线性预测、预算/预测超限告警、告警 ACK、CSV 导出 | `/api/billing/forecast`、`/alerts`、`/alerts/{id}/ack`、`/export`；告警按业务键去重，ACK 幂等；CSV 处理公式注入 |
| EvalOps 与发布门禁 | 评测集不可变快照、内容指纹、九维制品绑定、指标/回归/关键用例门禁、Judge 故障策略、重评与紧急豁免 | `/api/aiconfig/runtime-publish/gate/**`；豁免必须绑定 taskId 和候选内容 hash，记录操作人、原因与原判定 |
| ModelOps | 模型资产与部署分离、SecretRef 版本轮换、端点保存期出网校验、健康快照/事件、上线认证、影响分析、不可变路由版本、冲突校验、dry-run 与运行时真实选路 | `/aiconfig/model` 是统一入口；凭据响应只返回元数据；路由候选部署必须具有当前、未过期且与端点/凭据版本匹配的 PASSED 认证 |
| Trace / Replay | 调用时冻结 traceId、runtime revision/hash、模型/提示词/Agent/知识/工具版本与实验曝光；产生只读重放清单 | `GET /api/agent-call-stats/{id}/replay-manifest`；清单冻结输入、已录输出和分段，不会再次执行模型或工具 |
| MCP 治理 | 列表不返回配置内容；编辑详情要求 `mcp:edit`，敏感字段与 headers 只返回占位符，更新时按相同 JSON 路径合并旧值；真正调用工具要求 `mcp:edit` 并记操作审计 | `test-connectivity` 与 `debug/tools` 是只读探测，使用 `mcp:view`；`debug/call` 可能改变下游数据，使用 `mcp:edit` |
| 运行时配置完整性与语义缓存 | 消费端按发布端同一算法重算 contentHash；缺失、格式错误或正文不一致均 REJECTED；请求捕获实际配置 generation，hash 变化时先阻断缓存读写并清理旧代际，再原子应用新配置 | 旧在途请求的回写因 generation 不匹配被拒绝；校验、缓存清理或配置应用失败均保留旧配置；contentHash 是漂移/损坏检测，不是发布者签名 |
| 在线实验 | 不可变双臂定义、用户/会话稳定 SHA-256 分桶、曝光归因、真实调用日志指标、样本门槛、错误率/P95 护栏、到期与自动停止 | 控制面生命周期 `DRAFT/RUNNING/STOPPED/COMPLETED` 是 desired state；effective state 单列 `INACTIVE/ACTIVATING/ACTIVE/ACTIVATION_FAILED/DEACTIVATING/DEACTIVATION_FAILED`，由 ACTIVATE/DEACTIVATE 发布任务与 ACK 事实计算 |
| SLO / error budget | 按租户、Agent 或渠道配置可用性与延迟目标，同步评估短/长窗口，计算剩余错误预算与 burn rate | `POST /api/slo/policies/{id}/evaluate`；只有短窗和长窗同时超过 burn-rate 阈值才落唯一告警，无样本明确返回 `NO_DATA` |
| 业务结果—成本 | 按真实调用会话汇总技术成功、自动解决代理指标、转人工、token 完整度与 CSAT，并提供会话下钻 | `/api/business-outcomes/summary` 和 `/sessions`；接口随返指标口径与 availability，不把技术成功冒充真实业务解决 |

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

## 3. 上线前必须完成的环境配置

1. 启用 `admin.runtime-publish.nacos.enabled`，并使 admin 与 app-server 的 namespace、group、dataId 一致；
   多租户或租户专属实例必须显式配置 `customer-work.nacos.tenant-code`，不能依赖主 dataId 回落。
2. 配置相同的 admin 加密密钥与 app-server 解密密钥；密钥不得入库、入日志或出现在配置详情响应中。
3. MCP `config` 与 Nacos 运行时载荷中的 headers 尚未 SecretRef 化；数据库和 Nacos 必须启用最小权限 ACL、
   传输 TLS，并严格限制运行时 dataId 的写权限。
4. 生产开启 `customer-work.nacos.tenant-access-enabled=true`，设定快照主动回读间隔和最大陈旧时间。
5. 长期记忆使用 `provider=memory` + JDBC 存储，启用同意门禁与保留清理；隐私接口必须经用户 JWT 鉴权。
6. 为每个运行时实例配置唯一 `CW_RUNTIME_CONFIG_INSTANCE_ID`、至少 32 字节的独立 ACK token，并在
   `admin.runtime-publish.ack-identities` 中绑定租户与实例；多副本还要按实际副本设置 `minimum-ack-count`。
7. 为需发布的租户配置 Eval gate，完成模型认证与基线评测；紧急豁免权限与普通评测编辑权限分离。
8. 企业私网模型通过 `admin.model.egress.allowed-hosts`（`ADMIN_MODEL_EGRESS_ALLOWED_HOSTS`）显式列出 host；
   启用运行时热配置时还必须设置消费者侧 `customer-work.model.egress.allowed-hosts`
   （`CUSTOMER_WORK_MODEL_EGRESS_ALLOWED_HOSTS`）。两个名单都要列全公网厂商与私网模型 host。
9. 需要持续模型探测时，在核算外部调用预算后开启 `admin.model-health.enabled`；默认关闭时仍可手工探测，但不会自动巡检。
10. 根据真实流量和风险定义 SLO 窗口、目标、延迟阈值和 burn-rate 阈值；当前评估是同步触发，不应假设系统已自动周期调度。

## 4. 仍然存在的边界

| 边界 | 影响 | 当前运维建议 |
|---|---|---|
| 多 Pod ACK 没有冻结目标实例清单 | `minimum-ack-count` 默认为 1；APPLIED 只证明达到数量阈值，不证明发布时的全部实例都确认 | 按现网副本数显式配置阈值并监控 `PARTIAL`；后续在任务创建时冻结 instanceId 集合 |
| 实验 ACTIVATE 不是双臂离线实验评测 | 当前去除实验身份后校验基线候选，双臂依靠当前模型认证与线上错误率/P95 护栏 | 不得将门禁结果解读为“双臂均已完成离线效果对比”；高风险模型更换应先单独评测两臂 |
| 金额预测是简单线性外推 | 当前用“期内已用金额 / 已过天数 × 总天数”，没有季节性、节假日、增长趋势或置信区间 | 只作预算早期信号，不作财务承诺；对周期波动明显的租户使用外部预测平台 |
| 跨运行时退租依赖访问快照传播 | Nacos 投递、监听与主动回读存在延迟；在超过最大陈旧时间后新请求 fail-closed | 观测租户访问交付状态；快照发布失败时不要将退租宣称为已在全部运行时完成 |
| 外部长期记忆 Provider 尚未进入生产合规基线 | 百炼/Mem0/ReMe 没有统一的主体导出、擦除与可验证回执契约；生产门禁当前直接拒绝这类配置 | 开放前先补 Provider capability SPI、失败语义、回执审计和集成验证，再调整 `ProductionReadinessValidator` |
| MCP config/headers 尚未 SecretRef 化 | 页面不会回显密钥，但数据库权威配置与发布到 Nacos 的 MCP headers 仍是敏感载荷 | 依赖数据库/Nacos ACL、TLS、备份访问控制和审计；后续把 MCP 凭据迁入 SecretRef，并只发布引用或受控密文 |
| contentHash 不是签名 | 有 Nacos 写权限的人可改正文后自行重算合法 hash；当前校验只能识别传输损坏、漂移或误写 | 把 Nacos dataId 写权限视为生产信任边界并最小化；需要来源真实性时再增加带独立密钥的签名验证 |
| AgentScope 厂商 SDK 推理出网尚未固定解析与重定向策略 | 保存期会校验端点，连通性/健康探测及认证的连通性阶段已固定 DNS 结果并禁重定向；但认证能力检查与实际推理仍由各厂商 SDK 管理 HTTP/DNS | 不把探测链路保障宣传成全部推理请求保障；在 SDK 支持注入受控 HTTP client 前，以网络 egress ACL/代理限制模型目的地并监控 DNS |
| SecretRef 当前只有本地 AES 材料后端 | 数据模型保留 Vault/AWS/Azure/GCP/ENV 类型，但服务只实现 `LOCAL_AES` 创建与轮换 | 不得宣称已接入外部密钥管理器；生产需求外部 KMS 时先实现对应 Provider |
| Replay 仅为只读清单 | 生产工具可能发消息、改数据或触发审批，查看权限不能隐式升级为执行权限 | 真正重跑必须进入隔离环境并逐项授权 |
| 业务结果视图无会话级金额 | 现有金额事实只按“租户 + 自然日 + 模型”归集，无法无损关联 session；接口返回 cost `UNAVAILABLE` | 不按调用次数或 token 比例伪分摊；要算每次自动解决成本，先在调用事实上写入可结算金额或单价版本 |

## 5. 验证口径

本文的“已有能力”指代码、数据迁移、接口契约与自动化测试已落地。
它不等于已完成真实 Nacos 往返、多 Pod 全实例 ACK、生产模型 Key 认证、
或已登录浏览器的端到端验证。这些环境依赖验证必须在对应部署环境中单独执行并留存证据。
