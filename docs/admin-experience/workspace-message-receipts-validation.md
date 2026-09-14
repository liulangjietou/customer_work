# 工作区消息受理与核对验收（G7）

## 行为与职责

Chat 和 VibeCoding 普通/协作发送生成稳定消息 UUID。前端在拿到匹配的持久回执前保留原文和已上传附件；查询明确未受理且从未收到执行片段时，沿用原请求重试，不新增消息气泡。查询失败时保留原文，不推断任务未执行。已保存答复按回执中的助手消息标识回读，不能用其他轮次的答复填充。

调用链为两个工作区面板 → 对话 Store → 两个 API 封装 → 已认证 Controller → WorkspaceMessageAcceptanceService → 独立事务的 WorkspaceMessageReceiptStore → 原 Chat/VibeCoding/CollaborativeCoding 服务。受理编排位于 Service，完整归属与原子写入位于 Store；不改变模型框架消息 ID 或原有终态定义。

V107 只存请求指纹、完整归属和终态，正文和附件沿用原表。每条 SQL 显式限定服务端租户、实际发起用户、智能体、会话及渠道。唯一键决定执行权；相同 ID 改正文、附件、模式或资源均拒绝执行。受理独立事务先提交，外层事务回滚不能消除已取得的执行权。

已有客户端不传消息 ID 时保持既有协议。中断、进程故障和未记录终态只展示未知，不能宣传为后台任务已完成；本批不增加关闭页面后继续运行的任务调度。新登录同步释放两个 Store 的旧会话，迟到响应不能写入新身份。

## 失败证据与修复

- Store 单测首先复现发送立即清空输入/附件且没有稳定 ID；修复后覆盖 16 项受理和身份边界。
- 实际浏览器先复现重新登录仍保留上一账号的工作区私有草稿；根因是登录生命周期未释放独立 Pinia 会话 Store。
- 新增浏览器验收发现 VibeCoding API 重建请求体时丢弃消息 ID，Store 层模拟没有覆盖这个出站契约。新增普通/协作出站断言并修复该封装。
- 附件标签包含图标，纯文件名精确定位失败；页面快照证明附件仍在。测试改用明确的移除附件按钮定位，不算产品缺陷。
- 主动断网每例仅核销已断言的一条对应网络错误，其余未知 API、未处理异常和控制台错误继续失败。

## 最终本地验证

- 后端完整 Maven：4,443 项，0 失败、0 错误、7 跳过；实际五模块 Surefire XML 汇总见 `customer-workspace-final-v2-backend-summary.json`。跳过的是百炼 1、Nacos 4、PaddleOCR 1、真实 RAG 1，均未记为已验证。
- Admin 全部单测 303 项通过，类型与构建退出码 0；完整浏览器 256 项通过。
- H5 全部单测 108 项通过，构建退出码 0；完整浏览器 23 项通过。
- 停止/继续、消息来源及回执专项 32 项通过。额外视觉检查 8 项通过：Chat/VibeCoding × Ember/Night × 1440/390px；查看代表性截图，提示可读、正文保留、操作可达且没有横向溢出。
- 最终 31 个产品及测试文件匹配 `customer-workspace-final-v3-frozen.json`；其中 15 个后端和迁移文件与已通过完整 Maven 的源码一致。前端最终门禁 `customer-workspace-final-v3-front-gates.exit` 为 0。
- 本地完成后提交 Ready PR，远端 CI 状态单独登记；本地通过不代表主分支已合并或环境已发布。

证据保存在 `/fyoung/tmp/customer-workspace-*`；最终结果将在完整门禁结束后登记。数据库测试只创建和删除本次随机命名的数据库，不操作共享业务表。

## 完整门禁发现的迁移规则问题

首轮完整后端因 TableCollationAlignmentContractTest 两项失败停止；新表默认使用 utf8mb4_bin，违反项目统一表默认 utf8mb4_unicode_ci 的规定。正式迁移与 mysql/02 镜像均已改为统一表默认，租户、智能体、会话及渠道列显式声明 utf8mb4 字符集和二进制排序规则，保留精确归属比较。快照由正式工具重新生成。

修复后范围 Maven 共 24 项通过（真实受理 17、排序规则 5、迁移快照 2）；新增真实数据库用例证明大小写不同的租户对同一个客户端 ID 保有各自执行权。首轮证据存于 `/fyoung/tmp/customer-workspace-final-first-*`，两个自有随机测试库已清理。第二轮完整门禁记录在 `/fyoung/tmp/customer-workspace-final-v2-*`，仍待最终结果。

第二轮完整后端已通过：按根 pom.xml 的五个实际模块汇总 Surefire XML，共 4,443 项（0 失败、0 错误、7 跳过）。两个自有门禁数据库已清理，29 个冻结文件未变化。Admin 全部 303 项单测与类型/构建通过，完整浏览器和 H5 门禁仍待结果，尚未交付 PR。汇总证据为 `/fyoung/tmp/customer-workspace-final-v2-backend-summary.json`。

## 完整浏览器回归发现的问题与范围修复

第二轮 Admin 完整浏览器结果为 252 通过、2 失败（`customer-workspace-final-v2-admin-e2e-full.log`）。两项旧夹具返回执行/终态却没有新协议的 accepted 回执，后续发送因此按设计被拦截；已在正常停止/继续与检索来源夹具中补齐真实回执契约，仍保留专门测试覆盖回执丢失。

进一步发现两个工作区在收到 STOPPED 但回执未确认时，继续按钮仍显示可用。先新增两项浏览器测试稳定失败（`customer-workspace-continue-red.log`），再使按钮与点击处理同步受 pendingMessage 约束；源头 Store 已阻止发送，本次补齐用户可见状态。修复后的停止/继续、检索来源和消息回执范围共 32 项通过（`customer-workspace-continue-green.log`）。

15 个后端及迁移文件哈希与已通过的 4,443 项完整后端完全相同。固定全部 31 个产品/测试文件，第三轮前端门禁记录在 `customer-workspace-final-v3-*`，包含 Admin 全部单测、类型构建、完整浏览器与 H5 全部门禁；最终均通过。
