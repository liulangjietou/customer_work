# 客户消息受理与 H5 恢复验证记录

日期：2026-09-11。基于 `6ac8d798` 的独立变更，依赖工作区终态 PR #197。功能边界见 [受理契约](message-receipts-contract.md)。

## 完整门禁

源码冻结后完成以下检查，期间没有修改生产源码或测试：

| 检查 | 结果 |
|---|---|
| JDK 17 Maven `clean test`，全模块，不排除 Redis 持久化测试 | 3953 条，0 失败、0 错误、7 跳过；BUILD SUCCESS |
| starter | 1924 条，6 跳过 |
| app-server | 156 条，0 跳过 |
| customer-channel | 82 条，0 跳过 |
| admin-server | 1790 条，1 跳过 |
| gateway | 1 条 |
| Admin Vitest | 237 条，24 个文件全部通过 |
| Admin Playwright | 133 条通过 |
| H5 Vitest | 53 条，12 个文件全部通过 |
| H5 Playwright | 新接入 npm test 与既有 CI，6 条通过 |
| 两端类型检查和构建 | 通过 |
| 差异空白检查、凭据扫描、Compose 配置解析 | 通过 |

Java 全量执行耗时 5 分 14 秒。七条环境门控跳过分别涉及百炼真实调用、四条 Nacos 集成、PaddleOCR、外部 RAG；这些能力不计作本次真实服务联调成功。MySQL、Redis、MinIO 与 Docker 对应的既有集成测试实际执行。

## 失败复现和修复证据

- 保存失败后的同标识重试、配额拒绝后重试、重复投递原回执、内容冲突、会话范围和模拟重启：新增六条原实现均失败，再完成受理实现。
- 真实 MySQL 隔离库的三条验证覆盖：插入后异常回滚消息/工单/事件/Outbox；两实例同时未命中并发写入只提交一份；相同用户和客户端标识在两个租户独立受理。没有使用实例锁来替代数据库唯一性证明。
- 空消息、超过 TEXT 字节容量、越权会话错误关联、出站真实游标与流式会话范围均有修复前失败记录。
- 原存储层把查询故障转成空页，回执查询失败又被映射成业务冲突。新增故障注入验证三个读取入口均传播数据访问失败，鉴权切片验证回执查询返回 HTTP 失败，不伪装成未受理。
- 原恢复 GET 查询会消耗提问额度。三条参数化测试先失败，再验证恢复读取不扣额度，额度耗尽仍能读取，工单写操作仍受限制。
- 回执和异步回复/坐席转发的租户作用域分别验证：修复前出站线程看不到原租户；修复后投递发生于原租户，作用域退出后没有残留。
- H5 先复现了无法写出却清空输入、回执前清空草稿、迟到回执覆盖新编辑、重复投影、重连状态不刷新、片段被清空、多页记录缺口、其他会话片段串入、首次快照空隙、回复失败后光标不停止和卸载后旧连接被重新打开。

## 浏览器与视觉检查

6 条 H5 浏览器用例操作真实 Vue/Vant 页面，接口和 WebSocket 使用限定本机地址的隔离夹具，未命中预设的业务请求会失败。验证发送中保留草稿、受理后清理、回执丢失只查询、不存在时沿原标识重试、新草稿保留、重连关闭只读、101 条消息连续补齐，以及同步失败时片段仍可展开查看。

5 张状态截图已逐张检查，覆盖 360×800 和 390×844 视口。重试按钮点击区域至少 44px，长错误可换行，没有文档横向溢出。截图保存在本机 `/fyoung/tmp/customer-work-experience-20260910/implementation-evidence/receipt-*.png`，验收页为同目录上级的 `message-receipts-review.html`。

本机完整日志保留于 `/fyoung/tmp/`：

- `customer-work-receipts-full-maven.log`
- `customer-work-receipts-full-admin.log`、`customer-work-receipts-admin-build.log`
- `customer-work-receipts-full-h5.log`、`customer-work-receipts-h5-build.log`
- `customer-work-receipts-secret-check.log`、`customer-work-receipts-compose-check.log`

浏览器夹具不证明真实模型调用；MySQL 事务和鉴权切片不等于手机硬件与真实模型的全链路故障注入。坐席端受理与工作台、来源版本及后续运营流程继续按实施台账推进。
