# 草稿租户隔离与改进来源 SQL 兼容性验证

2026-09-15。第 7 项受控试用接入前发现的两个真实缺陷，先修复权限与生产查询边界。

## 调用链及根因

1. 我的草稿列表/恢复/保存/删除 → AgentDraftService → AiAgentDraftMapper → 租户 SQL 插件 → MySQL。旧实现只依赖插件附加的字符串等值条件，数据库 `utf8mb4_unicode_ci` 会把 `tenant-a` 与 `Tenant-A` 等同；相同用户编号因此可能读到另一精确租户的标题或正文。原测试只用差异明显的 tenant-a/tenant-b，没有覆盖大小写变体。
2. 知识缺口/改进记录查询及认领 → ImprovementCaseService → AgentImprovementCaseMapper → 生产租户 SQL 插件 → MySQL。`BINARY column = BINARY ?` 在 MySQL 可执行，项目使用的 JSqlParser 无法解析；原真实数据库恢复测试特意不安装插件以验证显式隔离，因此漏掉生产插件组合。

## 修复职责

- 归属判断放在草稿服务的共享查询条件中，同时限制登录人和精确租户；新增草稿显式写入当前可信租户，更新/删除同样保持精确身份。
- 精确租户的 MyBatis 条件在 TenantSqlConditions 定义，使用可被当前插件解析的 `CAST(... AS BINARY)`。条件值仍参数绑定，普通等值筛选保留索引使用机会。
- 改进来源查询与认领共用 sourceQuery，来源键也使用精确比较。真实插件回归还发现 LIMIT 与 FOR UPDATE 被重排；利用已存在的来源唯一约束移除多余 LIMIT，保持行锁。没有关闭租户插件，没有改数据库排序规则或历史迁移。

## 失败与修复证据

- `customer-draft-tenant-case-red.log`：真实库下大小写不同的租户读到草稿列表，1 项失败。
- 草稿首次修复尝试触发生产插件解析错误；`customer-draft-tenant-case-green2.log`：改为 CAST 后控制器/服务/真实库 7 项通过，0 失败、0 错误、0 跳过。
- `customer-improvement-tenant-parser-red.log`：给现有改进恢复用例加上生产租户插件，来源查询稳定报 SQL 解析错误，1 项错误。
- `customer-tenant-query-safety-scope2.log`：54 项通过（starter 共享常量门禁 2、Admin 52），0 失败、0 错误、0 跳过。包含真实插件下查询、已有认领、新建认领和租户/来源大小写隔离。六个产品与测试文件已冻结并核对 SHA-256。完整后端 4,639 项，0 失败、0 错误、7 跳过，退出 0，耗时 10:08。Admin 303 单测、H5 108 单测及两端构建退出 0；H5 23 项浏览器通过。Admin 首次完整浏览器门禁因外置盘断连中断，不能计为通过；2026-09-15 恢复后重新完成 293 项浏览器测试，退出 0，耗时 12.8 分钟，日志 customer-tenant-query-safety-admin-e2e-resumed.log。完整本地门禁已通过，交付与远端 CI 单独核对。

7 项跳过保留原门禁条件：Bailian 真模型 1、Nacos 相关 4、Paddle OCR 1、外部 RAG 服务 1。本次没有启用这些外部集成，不能记作已验收。实际 MySQL 租户、来源查询及恢复测试均运行。

审查追踪补充：Mapper XML 的 lockById 使用显式租户参数且原有 @InterceptorIgnore，不经过本次出错的 SQL 改写路径，保留原精确过滤。新条件全部参数绑定；CAS 版本、来源唯一约束、事务与行锁保持原语义。

本批没有新增迁移或前端产品改动。草稿受控运行器、上线聚合、全站业务状态、性能与 H5 真机仍继续按清单实施。
