# 改进用例创建的状态守卫

基线：0a9f7b55（PR #204），继承已验证的客服辅助版本。

## 问题和原因

已有 ImprovementCaseServiceTest 覆盖复评、发布及效果观察，没有检查 createEvalCase 在拒绝请求前是否已写入客服库。新增五个状态用例实测均失败：PUBLISHING、OBSERVING、VERIFIED、CANCELLED 先调用 EvalCaseStore.save 再拒绝；REEVALUATING 没有拒绝，仍会替换已用于复评的目标用例。

界面 canBind 已禁止以上五种状态。后端需要在持有当前改进项行锁时先检查状态，不能依赖旧页面是否仍显示按钮。

## 调用、职责和修复

调用链为 ImprovementClosurePanel → ImprovementCaseController#createEvalCase → ImprovementCaseService → 客服库 EvalCaseStore / BadcaseService，然后保存 Admin 库中的用例绑定。

状态编排属于 ImprovementCaseService。该方法先在 Admin 事务内 lockById，检查当前状态，再读取原始信号、创建客服端用例及绑定当前改进项。行锁覆盖整个创建和绑定过程，避免同一改进项在两步之间进入复评或发布；REEVALUATING 与界面现有约束保持一致。

没有修改已有用例保存的 upsert 语义，没有新增数据库表或状态机。客服用例与 Admin 状态仍处于两个数据库，这个本地事务不提供分布式回滚。此批解决已复现的拒绝时序与复评中目标替换问题；不同改进项之间的同编号创建竞争、跨库中断恢复需要独立验证，不能据此宣称已经解决。

## 验证

- 修改前，五项失败均来自实际服务方法的行为断言，无编译或夹具错误；四项捕获到禁止发生的 save 调用，一项没有按预期拒绝复评中请求。
- 修复后同组五项通过；新增成功路径锁定/创建/绑定顺序、创建失败保留原绑定断言。ImprovementCaseServiceTest 全部 15 项通过。
- 按纯后端守卫改动范围执行相关回归，共 56 项通过，0 失败、0 错误、0 跳过，覆盖改进状态机、自动化租约、信号 Mapper、迁移契约、运行发布任务与 Badcase 服务。两处 Java 源文件及测试在回归期间冻结，散列核对一致。
- 前端、协议与数据库结构相对基线无变化，沿用基线已完成的两端构建及完整浏览器验证；PR 仍由仓库 CI 执行全部必需检查。敏感模式扫描和 diff 检查通过。本批不把状态守卫单测当作真实分布式故障或跨库原子性验证。
