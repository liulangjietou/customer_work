# 坐席工作台验证记录

基线：客户消息回执批次 `f775865d`。本批在独立分支 `vibeCodingO/service-desk-workspace` 实施，无数据库迁移。

## 为什么原有测试未覆盖

原坐席回复测试主要断言一次落库及一次推送，没有覆盖相同客户端标识重试、关闭后的新回复、丢失响应后的查询、转派并发与凭证用途。原页面把 WS 写出当成发送成功，没有等待实际存储回执；旧 WS 对象的迟到回调、切单草稿及恢复超过一页的消息也缺少断言。

## 最小失败证据与修复后验证

| 风险 | 修复前证据 | 修复后断言 |
|---|---|---|
| 重试写入重复、内容冲突、关闭后仍可新回复、没有回执查询 | `customer-work-seat-receipt-before.log`，4 个失败 | 返回实际同一行；更换内容 409；关闭后新回复拒绝，旧回执仍可查询 |
| 发出请求立即清空草稿 | `customer-work-seat-draft-before.log`，真实 Vue 用例失败 | 等待回执保留原文；确认后不清空新草稿或另一工单 |
| 旧 socket 影响新连接 | `customer-work-seat-socket-before.log`，2 个失败 | 旧连接消息、错误和关闭事件失效；新连接生命周期正常 |
| 用户活跃时间回写覆盖转派 | `customer-work-seat-boundaries-before.log`，真实 MySQL 失败 | 锁定当前行再更新，转派后的坐席保留 |
| 浏览器实时凭证可直接调用坐席 HTTP 命令 | 同上，期望 403 的用例失败 | 新订阅凭证对 GET 与 POST 均拒绝；可信服务命令令牌保持兼容 |
| 客户消息按旧快照发给转派前坐席 | `customer-work-seat-transfer-route-before.log`，真实 MySQL 失败 | 当前工单行锁快照决定接收坐席 |
| 存储查询异常误报 409 | `customer-work-seat-read-failure-before.log`，期望 5xx 得到 409 | 回执查询及回复回读失败保持服务异常，不表示确定未保存 |
| 挂起工单显示不允许的直接转派操作 | `customer-work-seat-actions-before.log`，浏览器用例失败 | 只显示恢复处理与转回接单池；取消确认不调用接口；错误留在当前工单 |
| 低高度窗口的查询按钮被输入区挡住 | `customer-work-seat-receipt-position-before.log`，几何断言失败 | 待确认状态变化后，查询操作完整位于会话可见区域 |
| 新消息早于 scroll 回调到达时抢走阅读位置 | 首次完整浏览器回归 140 通过、1 失败；`customer-work-seat-reading-race-before.log` 固定时序后再次失败 | 更新消息 DOM 前读取实际滚动位置，不沿用迟到事件留下的底部标记 |
| 手动改变筛选后快捷视图仍选中旧状态 | `customer-work-seat-filter-before.log`，实际请求为 PROCESSING 时“待接入”仍选中 | 快捷视图直接从当前查询条件计算，不保存重复状态 |

上述日志保留在 `/fyoung/tmp`。早期转派日志中另有 Mockito 夹具的未完成 stubbing 异常，已修正；该夹具异常不作为产品缺陷证据。

最终后端定向回归 115 条通过：包括 9 条真实 MySQL 集成用例，覆盖用户与坐席消息、回滚、跨实例重复、租户边界和转派竞争。数据库采用隔离随机库、实际租户插件、实际事务和 outbox；故意绕过实例锁的用例证明唯一键及行锁的数据库行为。

专项浏览器用例 9 条通过，覆盖筛选快捷视图、草稿、回执、输入法回车、切单迟到结果、61 条消息补拉、重复 WS 帧、关闭快照、只读权限、挂起操作、阅读位置和小屏。

## 完整门禁

完整门禁已通过。源代码冻结清单保存在 `/fyoung/tmp/customer-work-service-desk-frozen-source.json`，Java 文件与完整 Maven 测试时的清单一致；最后一次筛选修复仅涉及前端，随后重新执行了完整前端门禁。

| 门禁 | 实际结果 | 日志（均在 `/fyoung/tmp`） |
|---|---|---|
| JDK 17 Maven 全模块 `clean test` | 3974 条，0 失败、0 错误、7 条环境门控跳过 | `customer-work-service-desk-full-maven.log` |
| Admin 完整单测和浏览器回归 | 247 条单测、142 条 E2E 通过 | `customer-work-service-desk-full-admin.log` |
| Admin 类型检查和生产构建 | 通过 | `customer-work-service-desk-admin-build.log` |
| H5 完整单测和浏览器回归 | 53 条单测、6 条 E2E 通过 | `customer-work-service-desk-full-h5.log` |
| H5 类型检查和生产构建 | 通过 | `customer-work-service-desk-h5-build.log` |
| 已纳入 Git 的凭据扫描、Compose 配置、差异空白检查 | 通过 | `customer-work-service-desk-secret-check.log`、`customer-work-service-desk-compose-check.log` |

实际 Redis 会话持久化、Sa-Token Redis 持久化及 9 条消息 MySQL 集成用例均执行通过。7 条跳过项为真实模型、4 条 Nacos、PaddleOCR 及真实知识检索服务；未将这些外部服务声明为已联调。后端与浏览器完整回归顺序执行，期间未修改源码。

## 页面证据与范围

实际 Vue 页面截图保存在 `/fyoung/tmp/customer-work-experience-20260910/implementation-evidence/desk-*.png`，可通过本机 `http://127.0.0.1:8769/service-desk-review.html` 查看。截图覆盖 1680px 桌面、低高度窗口、360px 队列与工单信息、390px 关闭工单。

浏览器使用封闭接口及 WS 夹具，验证前端行为，不代表真实客户写入或真实模型调用。后端持久化与权限通过独立测试验证。本批没有实际接通模型摘要、建议采用或知识引用；它们仍在总计划中。

## 部署与保留边界

需要先升级 App 服务，再配套升级 Admin 服务和前端并刷新旧页面。新订阅凭证不支持旧前端的 WS 回复方式；此前签发的命令令牌按原有效期失效，本批不提供立即撤销全部旧令牌的能力。

草稿是当前登录的内存数据，不承诺刷新后恢复；“已保存”只表示实际消息记录存在。异步通知、客户已读和业务办结是另外的状态。完整协议见 [坐席工作台契约](service-desk-contract.md)。
