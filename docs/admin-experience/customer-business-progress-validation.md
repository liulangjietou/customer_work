# 客户办理进度：契约与验证

基线为客户原文预览 `d271312d`。本批在 H5 当前会话增加只读的办理进度面板，分别展示工单、退款审批和执行状态；不触发退款申请、审批或支付操作。

## 调用链与职责

- H5 `Chat.vue` 用当前 `ticketId / sessionId / identityKey` 打开 `CustomerBusinessProgressPanel`。工单和退款各自请求、各自失败和重试，不把一方故障表现为空记录。
- 工单复用当前用户详情接口；退款新增 `GET /api/customer/user/sessions/{sessionId}/refund-approvals?page=1&size=10`。Controller 使用已认证用户和会话守卫，在异步读取前冻结租户。分页参数仅在此校验，页大小上限为 50。
- `UserRefundApprovalQueryService` 只编排两个项目已有标准存储：JDBC 和内存。自定义存储或未配置客服数据源返回 503，不能静默使用另一套空数据。
- `UserRefundApprovalDao` 使用客服专用数据源。SQL 同时约束审批、订单和会话的真实租户及用户归属，并对用户、会话标识执行精确匹配。分页计数和结果使用相同条件，不依赖可关闭的 MyBatis 租户插件。
- 内存审批存储按租户分区；无请求的定时器只遍历已经存在的分区。业务定时执行恢复原始租户标识，内部归一键不改变外部资源名称。
- 原生 `AfterSalesTools` 从本轮 `AgentInvocationIdentity` 恢复租户，审批登记还原工具入口的身份快照。上下文是框架注入参数，不进入模型可填的工具 schema。
- 新的内存边界影响旧审批、退款表单、业务分析和诊断入口。`ApiRequestTenant` 从已校验 API Key 取得身份；仅明确 `auth=false && tenant=false` 的匿名本地模式允许 DEFAULT。查询参数、会话前缀和线程残留值不能切换运营审批的租户。
- 匿名诊断仅为短期状态保留历史命名空间，与真实聊天的 `contextFor(...)` 一致；审批、审计和质检事实仍使用 DEFAULT 业务租户。已认证请求不回退到会话前缀所属的状态空间。

上述读取和存储权限放在各自真实的数据入口；H5 只控制展示与请求生命周期，不承担授权判定。

## 用户看到的行为

- 退款返回 `id / orderId / amount / approvalStatus / executionStatus / createdAtMs / decidedAtMs`。金额保持原始字符串，审批时间可为空；不返回操作员、内部原因、审批备注或执行故障细节。
- 待审批、拒绝、批准待核对、执行中、执行失败和执行完成分别展示。“已批准”不推断执行成功，“处理已执行”仍提示核对支付渠道，不能宣称到账。
- 404 表示不可访问，503 表示暂时不可读取，真实空列表才显示暂无审批记录。接口发送 `Cache-Control: no-store`。
- 关闭、切会话、换身份、路由离开都使旧请求失效并清除旧数据。工单接口若返回不同工单或会话，不显示其内容。分页刷新后记录减少时重新读取最后一个有效页。
- 工单详情继续对话携带原 `ticketId`；从进度打开订单后联系客户服务也保留原工单。普通订单入口仍兼容原 `orderId` 路由。
- 面板使用现有蓝色主题与 Vant，按钮至少 44px。360/390px 下正文可滚动，关闭和刷新入口固定；正向和反向 Tab 都留在对话框，Escape 关闭并恢复入口焦点。

## 最小失败证据与定向回归

测试原来主要直接调用设置好 ThreadLocal 的服务，或只模拟当前用户的数据，未覆盖实际 SQL 权限和框架切线程后的身份。新增测试针对这些边界，不以 mock 的空列表作为权限隔离证明。

| 风险 | 有效失败证据 | 修复后验证 |
|---|---|---|
| 订单原生 JDBC 未限定租户和所有者 | `customer-work-order-tenant-red.log`、`customer-work-order-owner-red.log`、`customer-work-order-context-red.log` | 17 项，含 11 项真实 MySQL DAO 用例和 6 项 HTTP Controller 用例 |
| 内存审批跨租户及定时器上下文 | `customer-work-refund-memory-tenant-red.log`：4 项失败、0 错误 | Starter 审批范围 61 项；APP 46 项，含真实数据库、HTTP 和装配验证 |
| 原生工具调用丢失审批租户 | `customer-work-business-progress-native-refund-red-valid.log` | 原生工具及售后相关 23 项通过 |
| 四个运营 HTTP 入口缺失可信租户 | `customer-work-operations-tenant-reactive-red.log`：真实响应式应用、实际过滤器、随机 Netty 端口，8 项失败、0 错误 | HTTP、上下文、API Key、操作员及关联服务共 53 项通过 |
| 匿名诊断误报已保存的短期状态不存在 | `customer-work-diagnostic-state-namespace-red.log`：5 项中 2 项失败、0 错误 | 真实运行上下文、内存 StateStore、SessionStateManager 与 HTTP；相关 33 项通过 |
| 工单、订单跳转丢失原会话；分页记录缩减 | `customer-work-business-progress-*-red.log` 中对应单项断言 | 路由、面板、API 和状态映射范围用例通过 |
| 点击弹层标题后 Shift+Tab 逃逸 | `customer-work-business-progress-focus-red.log`：1 项真实 Chrome 断言失败 | 修复后办理进度 5 项浏览器用例通过，含 360/390px、Escape 与焦点恢复 |
| 参考资料弹层存在相同的反向 Tab 缺口 | `customer-answer-sources-focus-review.log` 独立步骤及 `customer-work-source-focus-red.log` 1 项断言失败 | 同步修复列表标题的焦点回绕，最终 H5 23 项浏览器回归通过 |

日志均位于任务临时目录 `/fyoung/tmp`。首次普通 ApplicationContext 未装配过滤器的 API Key 测试、编译错误和不完整 ToolUseBlock 夹具不能作为有效失败证据；以表中实际业务断言为准。定向测试集合有重叠，不能相加冒充完整测试数。

## 完整门禁与交付

最终门禁均已完成，退出码为 0：

- JDK 17 完整 Maven：4,277 项，0 失败、0 错误、7 项环境条件跳过。Starter 1,995、APP 285、Channel 82、Admin 1,914、Gateway 1。日志为 `customer-work-business-progress-backend-full.log`，汇总为 `customer-work-business-progress-backend-summary.json`。
- Admin：255 项单测、204 项 E2E、类型与构建通过，日志为 `customer-work-business-progress-admin-*.log`。
- H5：最后一次参考资料焦点修复后重新完成 108 项单测、23 项 E2E、类型与构建，日志为 `customer-work-business-progress-h5-*.log`。修复前完整通过日志另存于 `h5-before-source-focus-*`。
- 差异检查、云凭据模式扫描、Compose 配置及 Admin 迁移镜像存在性检查通过。Author 与 committer 使用个人身份核对后提交。

后端回归期间 47 个产品及测试文件未变动；参考资料焦点问题在该门禁结束后修复，仅改 H5 组件和对应 E2E 断言。再次核对所有后端及 Admin 文件与已通过的冻结哈希相同，因此只重复 H5 完整回归。最终 `customer-work-business-progress-final-frozen.json` 含 48 个产品及测试文件，H5 最后门禁期间均未变动；前端清单含 16 个文件。

完整后端使用本次随机创建的独立客服库和后台库，已成功清理两个自有库。本批没有 SQL 迁移，也未修改共享开发库。本地跳过项为百炼、Nacos、OCR 和真实 RAG 等外部依赖；不能将这些条件跳过称为真实外部联调通过。

浏览器使用实际 Vue/Vant 组件及隔离 HTTP/WS 夹具，验证页面布局、状态、请求和跳转；真实身份和数据库隔离由独立后端测试验证。浏览器夹具禁止意外外部请求和业务变更。

## 继续处理的边界

- `MybatisAfterSalesBackend` 的存量退款写入链仍需单独验证并修复非默认租户、关闭租户插件时的实体归属与异步 SQL。当前原生工具测试证明审批登记身份，不等同于支付或整条售后写链验证。
- 物理设备软键盘仍待实机验收；360/390px 浏览器视口不能代替真机证明。
- 工作区受理与持久对账、知识候选、受控试用与评测发布、全站逐页业务状态和性能验收仍属于整体计划的后续工作。本批交付不表示总目标完成。
