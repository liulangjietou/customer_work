# VibeCoding Git 助手 + AgentScope 2.0 代码沙箱 —— 开发总结

> 分支：`feature/vibecoding-git-assistant`
> 涉及模块：`customer-admin-server` / `customer-admin-web`
> 测试基线：admin-server 从 127 → **175 全绿**
> 状态：改动未提交（全部在工作树），Local 模式完整验证通过，Docker 模式端到端跑通

---

## 一、VibeCoding Git 助手

落地《AI编码助手需求文档》里的三个 P0 能力。

### 后端
| 新增/改动 | 说明 |
|---|---|
| `ChatNodeKind` 新增 `FILE_CHANGE` | SSE 事件名直接返回 `file_change`，不走 `node:` 前缀 |
| `VibeCodingService.stream()` | 每个 `TOOL_RESULT` 后做增量快照 diff，新增/修改/删除文件实时以 `FILE_CHANGE` 事件推送；流结束兜底再检测一次 |
| `GitWorkspaceService` | 会话目录轻量 `git init` + 建立空 baseline 提交，`ProcessBuilder` 调系统 git；本轮变更 = 相对 baseline 的 diff |
| `GitAssistantService` | 一次性模型调用（不经 ReAct 工具循环）生成 diff 摘要 / commit message / PR description，独立线程池 + 超时兜底 |
| `VibeCodingController` 新增 3 接口 | `GET /git-diff`、`POST /commit-message`、`POST /pr-description` |
| 新增 DTO | `FileChangeEvent` / `GitDiffSummary` / `CommitMessageRequest` / `CommitMessageResponse` / `PrDescriptionRequest` / `PrDescriptionResponse` |
| `ResultCode` 新增错误码 | `NO_FILE_CHANGES` / `GIT_COMMAND_FAILED` / `GIT_ASSISTANT_AI_FAILED` |

### 前端
- `api/vibecoding.ts` 新增 3 个接口封装 + `types/api.ts` 对应类型
- `VibeCodingPanel.vue`：中列新增"本轮变更"实时时间线（绿/黄/红图标区分 CREATE/MODIFY/DELETE）+ "Git 助手"抽屉（diff 摘要 / commit message 风格切换 / PR description / 一键复制）

### 过程中修复的真实 bug
1. **baseline 建立时机错误**：`ensureRepo` 拖到 Git 助手点开时才建 → 本轮已写文件被基线吞掉，diff 恒空。修复：在 `stream()` 本轮写入前建立 baseline。
2. **`.git` 内部文件污染产物文件树**：`snapshot`/`buildFileTree` 遍历到 git 内部文件。修复：跳过 `.git` 子树。
3. **PR Description 契约不匹配**：接口用 `@RequestParam` 但前端发 JSON body → 走全局异常兜底。修复：改 `@RequestBody` + `PrDescriptionRequest`。
4. **业务异常被误吞**：`GitAssistantService` 的 `.exceptionally` 把 `NO_FILE_CHANGES` 也归成"AI 生成失败"。修复：`rethrow` 区分 BizException 原样抛出。

---

## 二、AgentScope 2.0 代码沙箱（Local/Docker 双模式）

参照 https://java.agentscope.io/v2/zh/docs/harness/sandbox.html

### 关键事实
- **Docker 沙箱零新增 Maven 依赖**：`agentscope-harness:2.0.0` 已内置 `DockerFilesystemSpec`，与 `LocalFilesystemSpec` 同一 `.filesystem(...)` 挂载点。
- **危险命令走自研正则拦截**（用户确认），不走框架 `PermissionMode.ASK`（现状 BYPASS，ChatService 未实现暂停确认闭环）。

### 后端
| 新增/改动 | 说明 |
|---|---|
| `AdminSandboxProperties` | `admin.sandbox.*` 配置（mode=local/docker、executeTimeoutSeconds、docker.image/memoryMb/cpuCount/network、guard.enabled/patterns），env 覆盖 |
| `AdminAgentInstanceFactory` 改造 | 按 mode 挂 `LocalFilesystemSpec` 或 `DockerFilesystemSpec`；vibecoding agent 挂载 `SandboxGuardMiddleware` |
| `SandboxGuardMiddleware` | 挂 `MiddlewareBase.onActing`，破坏性命令正则命中改写为 `[BLOCKED_BY_SANDBOX_GUARD]`，不打断主链路 |
| `VibeCodingService` prompt 引导 | 追加"生成代码后在沙箱内 javac/mvn test 验证"引导（阶段二自动验证） |
| `application.yml` | 新增 `admin.sandbox.*` 配置块 |

### Docker 模式连续挖出并修复的 3 个框架级坑
1. **sessionId 路径分隔符**：`HarnessAgent` 内部 `SessionSandboxStateStore` 给沙箱状态槽位拼出带 `/` 的 sessionId（四种 IsolationScope 全部硬编码），而 `MysqlAgentStateStore.validateSessionId` 拒绝含 `/`/`\` 的 ID → 每次对话必崩。
   **修复**：新增 `SandboxSafeAgentStateStore` 装饰器，Docker 模式下转义 `/`→`_` 再转发底层 store。
2. **SSE 同步异常挂起**：`HarnessAgent.stream()` 在沙箱资源获取失败时（docker run 超时等）是方法调用**同步抛异常**，绕过 `onErrorResume`，SSE 响应头已提交 → 连接挂起不报错不关闭，前端永远卡"生成中"。
   **修复**：`ChatService.chatStream` 用 `Flux.defer` 包裹 `streamEvents`，把同步异常转成 error 信号让 onErrorResume 接管。
3. **commons-lang3 缺类**：harness 的 tar 工作区投影（`WorkspaceProjectionApplier`）依赖 `commons-compress` → 需要 `commons-lang3.ArrayFill`（3.14+），项目锁定 3.13 → `NoClassDefFoundError`。
   **修复**：`customer-admin-server/pom.xml` 显式覆盖 `commons-lang3` 到 3.18.0（只覆盖本模块，不动根 pom）。

### Docker 模式端到端实测证据
后端日志证实完整链路：`write_file` 写入 `sessions/diag-full-004/Hello.java` → `execute` 执行 `javac Hello.java && java Hello` 返回 `Exit code: 0\n\nhi` → 沙箱状态持久化成功，0 未处理异常。系统提示含 `Sandbox root: /workspace (container id: sandbox-xxx)`、`The host filesystem is not directly accessible`，且 Agent 用绝对路径 `working_directory` 被沙箱拒绝（路径越界防护生效）。

---

## 三、需知悉的结论

1. **Local 模式已完整验证**（危险命令拦截、自动编译验证引导、Git 助手、file_change 全部生效）。**Docker 模式对话+沙箱执行链路也已跑通**。
2. **Docker 模式的架构副作用**：产物文件写在**容器内**，宿主机侧的产物文件树 / file_change 事件 / Git 助手会读不到——这是 Docker 隔离的固有特性，不是 bug。后续若要 Docker 模式配套这些功能，需额外实现容器↔宿主机文件同步。
3. **阶段三（交互式运行面板）、阶段四（独立沙箱管理页面）未做**——计划中它们是独立增量，本轮聚焦打通并验证地基（阶段一 Git 助手 + 阶段二沙箱）。

---

## 四、改动清单（27 文件，未提交）

**后端新增**：`AdminSandboxProperties`、`SandboxGuardMiddleware`、`SandboxSafeAgentStateStore`、`GitWorkspaceService`、`GitAssistantService`、6 个 DTO、5 个测试类
**后端改动**：`pom.xml`、`ResultCode`、`ChatNodeKind`、`ChatService`、`AdminAgentInstanceFactory`、`VibeCodingController`、`VibeCodingService`、`ChatServiceTest`、`VibeCodingServiceTest`
**前端改动**：`api/vibecoding.ts`、`types/api.ts`、`views/workspace/VibeCodingPanel.vue`

## 五、环境恢复提示

- 我起的临时资源已清理：8083 后端已停、临时 maven 沙箱容器已删、vite 配置已恢复指向 8082。
- 需手动恢复：8082 admin-server 和 5174 前端在验证过程中被停过，IDE 里按平时方式重启即可；**记得去掉临时加的 `ADMIN_SANDBOX_MODE=docker` 环境变量**，回到默认 local 模式。
