# 三条订单链的权限与写入正确性

本批承接登录和主题保留 PR #213。真实订单操作必须同时满足调用者身份、租户和业务状态；成功文案只能描述已经保存的事实。

## 调用链与职责

1. 原生 AgentScope Toolkit → OrderTools → RuntimeContext 中的 AgentInvocationIdentity → OrderBackend SPI → MybatisOrderBackend → OrderMapper。入口负责从可信运行上下文取身份，后端在方法调用时冻结 USER 身份并在订阅线程恢复租户，Mapper 将租户、精确用户归属和取消状态写入同一条 SQL。原五个工具的业务参数和后端 SPI 保持不变，RuntimeContext 不进入模型工具参数。
2. APP 用户 JWT → UserOrderController → UserOrderDao → cw_order。用户仍取认证主体；DAO 的用户 ID 比较改为字节精确，防止数据库不区分大小写或尾空格把其他主体当成本人。列表和详情均覆盖。
3. Admin Sa-Token 权限 → UserOrderController → UserOrderService → CustomerWorkTicketClient/CustomerWorkClientConfig → 签名 X-Agent-Token → APP AgentAuthWebFilter → AgentOrderController → OrderDirectoryService → OrderMapper。Admin 的查看、编辑权限继续分别检查；APP 在切换阻塞线程前固定验签租户，目录 SQL 不依赖可关闭的租户插件。用户名 JOIN 同时限制租户和精确用户 ID。

取消判断属于实际写入的业务条件，必须与 UPDATE 原子执行；改址零行时由目录/工具后端查询当前归属和地址，避免把并发删除记成成功，也避免把重复提交相同地址记成不存在。没有改造整个订单领域模型，没有新增迁移或引入支付、仓储副作用。

## 失败复现与修复

| 风险 | 旧测试为何遗漏 | 失败证据与修复 |
|---|---|---|
| 原生工具继承错误线程身份 | 直接 Java 调用与 Mock 后端未经过 RuntimeContext | 原生 Toolkit 探针 15 项中 10 失败；OrderTools 只信任隐藏运行上下文，缺失时不继承残留身份 |
| 工具查改他人订单、APP 用户别名、坐席跨租户/用户名 JOIN | 单库种子和仅 Mock Service 未建立跨主体、跨租户数据 | 真实 SQL/HTTP 64 项中 36 失败；归属与租户进入实际 SQL，大小写与尾空格不能匹配同一用户 |
| 查询后取消覆盖并发发货、并发删除仍返回成功 | 原测试只有顺序状态变化 | 第二 JDBC 连接在目标 UPDATE 前提交发货/删除，旧实现失败；新 SQL 取消状态条件和零行事实判定阻止虚假成功 |
| 数据库故障误报不存在或泄漏私有 SQL | 只有正常持久层结果 | 真实 MySQL trigger 注入失败；工具抛脱敏失败信号，坐席 HTTP 返回 503，数据不变 |
| 重复地址被驱动零变更行误报不存在 | 原连接只用默认 found-rows 语义 | `useAffectedRows=true` 实库测试稳定失败（185 项中唯一错误）；零行时核对自有订单当前地址后确认，其他地址不确认 |

失败日志位于 `/fyoung/tmp/customer-order-next-tests/`、`/fyoung/tmp/customer-order-repeat-address-red-backend-full.log`。全部测试库使用本批随机名称创建，结束后只清理本次创建的数据库。

## 验收范围

- 原生 USER 身份与缺身份、伪主体、大小写/尾空格、跨用户、跨租户；实际 Toolkit 与真实 SQL 两层相接，拒绝操作核对完整订单快照不变。
- 客户 JWT HTTP 与 DAO 的精确归属；坐席 HMAC → Controller → 工作线程 → 真实目录 SQL 的租户插件开启/关闭对照。
- Admin 真实 Sa-Token/权限拦截、真实 Service/RestClient 与本机 HTTP 记录端，验证拒绝请求不出站、签名主体来自登录会话、签名租户来自服务器上下文、分页参数以及上游 404/409/503 翻译。记录端没有假装真实 APP 数据库；APP 端另有真实 SQL 集成测试。
- 合法自有订单及同租户坐席的查询、改址、取消；取消沿用现有可取消状态，成功只陈述“已取消，未执行退款”，催发货仅陈述已登记标记。
- 兼容旧 OrderTools 非注解重载、旧 OrderBackend SPI 和售后 findOwned 调用；无签名租户的旧坐席凭据访问订单现明确返回 401，不能依赖关闭租户插件绕过边界。

## 门禁状态

初步 79 项通过；补充旧调用方、Admin 权限边界及重复地址修复后，范围回归 245 项通过，0 失败、0 错误、0 跳过（starter 185、APP 36、Admin 24），两座门禁库已清理。16 个产品/测试文件冻结后，全模块 Maven clean test 通过：4,418 项，0 失败、0 错误、7 项外部条件跳过。两座自有门禁库清理完成。Admin 285 项单测、类型/构建及完整浏览器 247 项全部通过；H5 108 项单测、类型/构建及完整浏览器 23 项全部通过，所有退出码为 0。最后逐一复核 16 个冻结文件哈希一致。交付分支 `vibeCodingO/order-authorization`，父分支为 #213 的 `vibeCodingO/login-theme-preservation`；提交后核对远端 CI，未合并 main。
