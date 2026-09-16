# 售后申请归属与后台订单交互验证

基线 `329bcbe6`，承接办理进度 PR #211（四项远端 CI 均通过）。本批补齐真实售后申请创建的身份边界，并验收后台订单列表、详情、改址与取消流程。整体体验计划继续执行。

## 调用链与职责

- 普通客服 HTTP、WS、AGUI 由 `ToolRegistrar` 注册带真实会话和审批服务的 `AfterSalesTools`；`MultiAgentOrchestrator` 的售后专家不带审批服务。两种装配都通过实际 Toolkit 与 JDBC 验证，不能将无审批服务解释为仅生成演示文案。
- 七个原生工具均从框架注入的 `RuntimeContext` 获取完整可信 `AgentInvocationIdentity`。调用及退款审批回调临时安装身份与租户，结束后恢复线程原上下文；这些字段不进入模型可填的业务参数。
- `MybatisAfterSalesBackend` 在公共方法调用时冻结完整身份，在延迟 SQL 时恢复租户。实际 JDBC 只接受已认证 USER 的租户与用户 ID；API Key、后台用户和 IP 缺少此链路的订单所有者授权事实，不能当作客户 ID。
- 申请创建采用 `INSERT … SELECT`，在同条 SQL 内校验订单、租户及精确用户归属。查询使用相同范围；不依赖可关闭的租户插件。用户 ID 使用二进制精确比较，租户保持项目已有的不区分大小写语义。
- 创建失败保留 `Mono error`，不会进入工具的审批登记。数据库原异常仅记录于服务端英文错误码日志；公开错误不携带 SQL 根原因，避免 Toolkit 解包后将内部文本带给模型。
- 七个 `AfterSalesBackend` 的 `Mono<String>` 方法签名与原有 Java 工具调用入口保持兼容。金额和退款资格继续沿用现有输入契约，本批不引入支付服务或新的退款政策。
- 后台订单通过 `/api/ticket/orders` 调用已有管理端代理。列表复用 `usePagedList` 的错误、旧数据与请求顺序管理；详情和表单生命周期由页面管理。身份或权限变化立即关闭旧详情、表单和本页确认框。
- Admin 公共请求层用实际已发送配置中的 Authorization 对照当前登录凭据；旧请求的登录失效、强制改密与全局错误提示不能改变新登录。当前凭据的真实失效仍清理并跳转，错误信号继续交给页面处理。

## 用户可见行为

- 退款、退货、换货和发票申请只描述实际登记状态。批准不能解释为到账，不承诺未确认的时效、短信、库存、邮件或价保结果。工具说明同步采用这一事实边界。
- 订单首次加载失败提供就地重试，刷新失败标明旧数据；切换筛选或订单后，迟到响应不能覆盖当前结果。
- 改址和取消在提交期间锁定表单，失败保留输入。撤回取消确认不会抛出未处理异常；写入结束后列表独立刷新，键盘重开表单不会被旧请求永久锁定。
- 编辑权限撤销后不再提供写操作；新旧身份之间清理详情和表单。公共请求层的旧登录失效响应防护另有独立回归。
- 390px 下筛选区分两列、查询占一行，查询和表单提交按钮至少 44px。详情标签保持 88px 宽，长商品、地址与物流文本换行；后端文本通过普通文本节点显示。

## 失败证据与定向回归

旧售后测试主要直接调用默认租户下的后端或模拟服务；后台订单只有挂载与无溢出检查，未覆盖真实异步交互和故障分支。

| 风险 | 有效 RED | 修复后证据 |
|---|---|---|
| 七条原生工具丢失完整身份、无身份继承残留值、审批回调身份 | `customer-work-after-sales-native-identity-red-boundaries.log`：32 项中 14 失败、0 错误 | 工具/原生/审批/Factory/MultiAgent/预算范围 64 项通过 |
| 实际售后写入、订单归属、延迟身份和故障后审批 | `customer-after-sales-business-scope-valid-red.log`：29 项业务断言失败、0 错误 | 最终 SQL 与旧后端/目录范围 62 项通过，含 36 项新真实 SQL 用例 |
| 大小写与尾空格的用户 ID 别名 | `customer-after-sales-user-alias-red.log`：2 项失败、0 错误 | 七条自有订单路径的精确主体比较通过 |
| Toolkit 将 SQL 根异常带给模型 | `customer-after-sales-database-error-red.log`：5 项中 1 失败、0 错误 | 同一真实 MySQL trigger 故障只返回公开错误、无审批；另 4 项租户插件开启的 SQL 兼容通过 |
| 订单错误状态、迟到响应、表单及权限变化 | `customer-after-sales-admin-valid-red.log`：11 项中 10 失败 | 首轮 11 项浏览器回归通过 |
| 窄屏详情标签被压成单字竖排 | `customer-after-sales-admin-label-red.log`：实测标签宽 30.84px，最小断言失败 | 改用组件标签宽度属性后重新通过 11 项浏览器回归 |
| 写后刷新挂起时键盘重开导致表单锁死 | `customer-after-sales-admin-reopen-red.log`：2 项失败 | 两个写操作与列表刷新生命周期分开，纳入最终订单回归 |
| 旧登录失效/改密/错误响应影响新登录 | `customer-after-sales-admin-request-identity-valid-red.log`：5 项中 3 个真实断言失败，2 个当前登录对照通过 | 独立浏览器 5 项与请求层 14 项单测通过；包括匿名、HTTP 错误与下载错误提示 |

所有日志保留在 `/fyoung/tmp`。首次 Toolkit 输入不完整、不可变拦截器列表的测试装配错误、关闭按钮英文定位错误均已修正，不能计作有效业务 RED。定向测试集合有重叠，不相加冒充完整测试数。

## 完整门禁

2026-09-13 后端及前端单测/构建完成；2026-09-14 外置盘恢复后核对 19 个冻结源文件哈希全部一致，重新完整运行 Admin 浏览器回归。原中断执行不算通过。

| 门禁 | 实际结果 |
|---|---|
| JDK 17 Maven 全模块 clean test | 4,336 项，0 失败、0 错误、7 项环境条件跳过，退出码 0 |
| Admin Vitest | 269 项通过 |
| Admin 完整 Playwright | 222 项通过，9.4 分钟，退出码 0 |
| H5 Vitest / Playwright | 108 项 / 23 项通过，浏览器退出码 0 |
| 两端类型检查与生产构建 | 均通过，退出码 0 |
| 静态与结构检查 | git diff --check、凭据扫描与 docker compose config 通过 |

后端明细：starter 2,054 / app-server 285 / channel 82 / admin 1,914 / gateway 1；七项跳过涉及百炼、Nacos、真实 OCR 与外部 RAG 条件，不属于已完成的真实服务联调。专用随机库 `cw_aftersales_gate_7103d78c4e08`、`admin_aftersales_gate_7103d78c4e08` 均在 runner finally 清理，没有修改共享开发库。没有新增 SQL 迁移。

权威日志位于 `/fyoung/tmp/customer-work-after-sales-business-scope-{backend-full,admin-unit-full,admin-e2e-resumed,h5-unit-full,h5-e2e-full}.log`；后端 XML 汇总为同前缀 `backend-summary.json`。最新浏览器截图来自 `customer-after-sales-admin-e2e-resumed`，已查看桌面订单列表与 390px 长详情，实际页面画廊为 `order-management-review.html`。截图是隔离接口数据，不证明尚未修复的坐席后端订单边界。

Java 复核覆盖真实工具装配、隐藏 RuntimeContext、延迟身份恢复、显式 SQL 与公开异常边界。原 SPI 和直接 Java 调用入口保留；跨存储审批一致性与旧订单链问题留在明确后续条目。完整门禁期间产品及测试源码未变化。

## 后续边界

- 本批验证申请创建与读取，不证明支付到账；工单与审批跨存储的一致性、旧订单工具及专用后台订单数据链的剩余边界另行验证。
- 公共请求层保护的是它自身的登录与提示副作用。独立只读审查指出，旧菜单初始化被拒绝后，存量路由守卫仍无条件清理登录；这条调用方路径尚未动态复现，列入后续处理。成功响应也仍由页面的身份生命周期管理，不能将本批推广为全站任意迟到响应均已验证。
- Admin 浏览器使用实际 Vue/Element Plus 页面与严格隔离接口夹具；SQL 与可信身份由独立真实数据库用例证明。390px 视口不代替物理设备软键盘验收。
- 工作区受理及持久对账、知识候选、受控试用和评测发布、其余后台页面的业务状态与性能验收仍属于已批准计划的剩余工作。
