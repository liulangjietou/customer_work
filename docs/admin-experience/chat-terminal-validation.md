# 工作区终态验证记录

日期：2026-09-11。基于首批 `cf859bdd`，范围为 [实时终态契约](chat-terminal-contract.md)。

## 完整门禁

| 检查 | 当次结果 |
|---|---|
| JDK 17 Maven 全模块 `clean test` | BUILD SUCCESS；3927 条，0 失败、0 错误、7 条环境门控跳过 |
| starter | 1917 条，6 条跳过 |
| Admin 后端 | 1790 条，1 条跳过 |
| 应用服务 / 渠道 / 网关 | 137 / 82 / 1 条，全部执行通过 |
| Admin Vitest | 24 个文件、237 条通过 |
| Admin Playwright | Chrome 单 worker，133 条通过，包含新增 8 条终态场景 |
| H5 Vitest | 11 个文件、38 条通过 |
| Admin 与 H5 类型检查、生产构建 | 均通过 |
| Git 差异检查、凭据模式扫描、Compose 配置解析 | 均通过 |

后端完整日志为 `/fyoung/tmp/customer-work-terminal-full-maven.log`；Admin 完整日志为 `/fyoung/tmp/customer-work-terminal-full-admin.log`。两端构建与 H5 单测日志保留在同目录，以 `customer-work-terminal-` 为前缀。

7 条跳过为 Bailian 真实模型、4 条 Nacos、PaddleOCR 真实服务和 RAG 搜索真实服务用例。MySQL、Redis、MinIO 可用的集成用例均实际执行，未排除 RedisSessionPersistenceTest。

## 先失败后修复的边界

- 两个工作区在 EOF、非法终态、停止请求与最终完成竞态下不得把连接关闭显示为成功或已停止；刷新历史后只有明确 STOPPED 恢复继续入口。
- 后端流兜底必须发送失败终态；普通结束必须直接回读状态存储，不能使用框架默认 getter 或内存状态声称落库。
- 实际 ReAct 与 Harness 配合确定性模型，验证普通回复记录真实模型原因、保存成功及保存失败，避免只用手工构造的事件自证。
- 编码先保存产物再发终态；保存失败保持未知并保留本地文件；取消只保存一次；冻结后的对象键不受异步线程租户变化影响。
- 编码失败不能记录成功或继续审查；协作等待后续角色结束；重构拒绝或确认超时必须有终态且不执行修改。

最小失败日志包括 `customer-work-terminal-before.log`、`customer-work-history-resume-before.log`、`customer-work-terminal-server-before.log`、`customer-work-coding-terminal-before.log`、`customer-work-collab-terminal-before.log` 和 `customer-work-task-terminal-before.log`，均位于 `/fyoung/tmp`。

## 视觉与验证边界

新增浏览器场景使用真实 Vue 组件和明确的接口夹具。桌面普通对话为 1440×900，移动编码工作区为 390×844，分别覆盖 UNKNOWN、FINAL、STOPPED、FAILED。8 张截图逐张核对：状态与提示可读、草稿保留、停止入口准确、没有横向文档溢出。截图使用 Playwright 的输出目录，兼容本机与 CI。

浏览器夹具不证明真实模型或业务工具写入已联调；确定性模型与存储集成测试分别提供对应层级的证据。异步审计提交不等于审计已落库，historySaved 只证明主 Agent 本轮输入和回复，协作角色历史与完整终态的重连对账尚待受理记录接通。

本次没有数据库迁移。受理回执、持久化幂等、H5 与坐席断线恢复以及其余页面业务验收继续按实施计划推进。
