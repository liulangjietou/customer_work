# 智能体客服后台管理系统 —— AI 编码助手需求文档

> 基于 AgentScope Java 2.0.0 GA（`main` 分支，官方文档 https://java.agentscope.io ）与现有
> `customer-work-starter` 能力进行二次开发，载体为 `customer-admin-server`（后端）+ `customer-admin-web`（前端）。
> 版本：v2.0（2026-07-13 修订）
> 目标读者：产品、后端/前端开发、测试
> 状态：v2.0 待评审
>
> **v2.0 修订说明**：v1.0 中的两个 P0（实时 file_change、Git 工作流集成）与 Sandbox 能力已全部交付并合入 main
> （commit `1ed6d77`，admin-server 测试 127 → 175 全绿）。本版将已交付能力沉淀为"第 2 章 能力基线"，
> 剩余需求按「对项目重要性 × 提升开发效率 × 已有基础成熟度」重排优先级，并修正 v1.0 与实际实现不符的
> 接口、数据模型与配置项描述。

---

## 1. 背景与目标

### 1.1 背景

`customer-admin-server` 的 **VibeCoding** 能力已完成两轮迭代：

- **一期（降级方案）**：Agent 读/写会话 workspace（`sessions/{sessionId}/`）下的代码文件，对话结束后快照对比返回变更清单。
- **二期（已交付，详见第 2 章）**：文件变更实时流式推送（`file_change` 事件）、Git 助手（diff 摘要 / commit message / PR description）、
  AgentScope 2.0 代码沙箱（local/docker 双模式）+ 危险命令拦截。

当前阶段的主要矛盾已从"看不见 Agent 在做什么"转移到：**改错了不能一键恢复、代码质量靠人工逐行看、
生成的代码对不对要自己跑**。这三点是日常使用 VibeCoding 时最直接的效率损耗，也是本版 P0 的来源。

### 1.2 目标

在不改动 AgentScope 2.0 核心框架的前提下，基于已交付基线（`VibeCodingService`、`GitWorkspaceService`、
`GitAssistantService`、`AdminAgentInstanceFactory` 沙箱装配、`SandboxGuardMiddleware`）持续构建面向开发团队的
**AI 编码助手**，本版覆盖：会话回滚、AI Code Review、生成代码自动验证闭环、Plan Mode 人工确认、
交互式运行面板、Docker 沙箱补齐、Bug 诊断、自动化重构、多 Agent 协作编程、代码知识库问答。

### 1.3 成功指标

| 指标 | 状态 |
|---|---|
| file_change 事件实时可见，延迟 < 1s | ✅ 已达成（TOOL_RESULT 后增量快照推送） |
| commit message 人工无需修改直接可用比例 ≥ 50% | ✅ 已交付，待线上度量 |
| 误操作后一键回滚，恢复到对话前状态 ≤ 3s | 🎯 本版 P0-1 |
| 典型问题 diff（NPE/SQL 注入/硬编码密码）CRITICAL 检出率 ≥ 90% | 🎯 本版 P0-2 |
| 生成代码在沙箱内自动编译+测试，结构化报告返回率 100% | 🎯 本版 P0-3 |
| 代码知识库问答 Top-3 检索命中率 ≥ 70% | 🎯 本版 P3-2 |

---

## 2. 已交付能力基线（v1.x，main@`1ed6d77`）

新需求一律在此基线上叠加，不重复建设。此处只列对后续需求有约束力的事实
（实现细节现场读 `customer-admin-server` 的 `workspace/vibecoding` 包源码）。

| 能力 | 实现要点 | 与 v1.0 需求稿的差异 |
|---|---|---|
| 实时 file_change 事件 | `VibeCodingService.stream()` 在每个 `TOOL_RESULT` 后做增量快照 diff，以独立 SSE 事件 `file_change` 推送（`ChatNodeKind.FILE_CHANGE`），流结束兜底再检测一次 | 未采用 v1.0 的"500ms 后台轮询"或"框架 Hook"方案；`FileChangeEvent` 仅 `operation/path/description` 三字段，**无 RENAME/oldPath** |
| Git 助手 | `GitWorkspaceService`：会话目录轻量 `git init` + 空提交 baseline（`stream()` 本轮写入前建立），本轮变更 = 相对 baseline 的 diff；`GitAssistantService`：一次性模型调用（不经 ReAct 工具循环）生成摘要/commit message/PR description，独立线程池 + 超时兜底 | 只做只读 diff 与文本生成，**不做真正的 git commit/push**（v1.0 的"自动执行 git add + commit"可选项未做，业务提交仍由开发者本地完成） |
| 代码沙箱 | `admin.sandbox.*` 配置（mode=local/docker、超时、docker 镜像/内存/CPU/网络）；`AdminAgentInstanceFactory` 按 mode 挂 `LocalFilesystemSpec` / `DockerFilesystemSpec`（框架内置，零新增依赖）；Docker 模式端到端实测跑通 | 配置前缀是 **`admin.sandbox.*`** 而非 v1.0 写的 `customer-work.harness.*`（admin-server 排除了 starter 自动装配） |
| 危险命令拦截 | `SandboxGuardMiddleware` 挂 `onActing`，破坏性命令正则命中改写为 `[BLOCKED_BY_SANDBOX_GUARD]`，不打断主链路，默认开启 | 走自研正则拦截，**未走框架 `PermissionMode.ASK`**（现状 BYPASS，确认闭环见 P1-1） |
| 沙箱内自动验证引导 | VibeCoding prompt 追加"生成代码后在沙箱内 javac/mvn test 验证"引导 | 仅 prompt 引导，验证结果混在文本流中，无结构化 `test_report`（见 P0-3） |
| 文件树/文件读写 | `GET /files`、`GET/PUT /file-content`、`GET /artifacts`、`GET /sandbox-mode` | — |

**已知架构约束（对后续需求有直接影响）**：

1. ~~**Docker 模式产物在容器内**~~：**已由 P1-3 解决**。原约束是宿主机侧产物文件树 / file_change / Git 助手
   读不到容器内文件；P1-3 用 bind mount 把宿主机会话目录挂进容器 `/workspace/sessions`，产物实时落宿主机，
   依赖宿主机文件系统的功能（回滚 / Review / Git 助手 / test_report）在 Docker 模式与 local 等价可用。
2. **`HarnessAgent.stream()` 同步异常**：沙箱资源获取失败时同步抛异常，已用 `Flux.defer` 包裹兜底（`ChatService`），
   新增 SSE 接口须沿用该模式。
3. **Docker 模式 sessionId 转义**：`SandboxSafeAgentStateStore` 装饰器负责 `/`→`_` 转义，新增涉及沙箱状态
   持久化的功能须复用。

---

## 3. 功能需求总览（v2.0 优先级）

排序依据：**对当前项目重要性 × 对开发效率的直接提升 × 已有基础成熟度（越现成越先做）**。
P0 三项共同特征：地基已在二期打好（git baseline / diff 能力 / 沙箱执行链路），属于"最后一公里"收口，
投入小、每天都用。

| 优先级 | 功能模块 | 核心能力 | 依托的已有基础 | 预估 | 验收标准 |
|---|---|---|---|---|---|
| **P0-1** | 会话一键回滚 | 撤销本次会话全部文件修改 | `GitWorkspaceService` baseline 机制已就位 | 0.5~1 天 | 点击后 ≤3s 恢复到对话前状态 |
| **P0-2** | AI Code Review | 对本轮 diff 自动审查，输出结构化意见 | diff 能力 + `GitAssistantService` 一次性调用模式 | 2~3 天 | 典型问题 CRITICAL 检出率 ≥ 90% |
| **P0-3** | 生成→验证→修复闭环 | 沙箱内编译/测试，结构化 `test_report`，失败自动修复 | 沙箱执行链路已通 + prompt 引导已有 | 3~5 天 | 测试结果 100% 结构化返回，失败自动修复 ≤3 轮 |
| **P1-1** | Plan Mode 人工确认闭环 | 高风险操作先出计划、等确认再执行（HITL） | 框架 `PermissionMode`；starter 已有 Approval Store SPI 模式可参考 | 5~8 天 | 删除文件/批量修改前必须停下等确认 |
| **P1-2** | 交互式运行面板 ✅ | 页面内直接对沙箱执行命令，输出流式回显 | 沙箱 execute 链路已通 | 已实现 | 常用命令（mvn test/java）免切终端 |
| **P1-3** ✅ | Docker 沙箱补齐 | 容器↔宿主机产物同步（bind mount）+ `DockerSandboxIntegrationTest` | Docker 链路端到端已跑通 | 3~5 天 | Docker 模式下文件树/file_change/Git 助手可用（已达成） |
| **P2-1** | 智能 Bug 修复 ✅ | 根据异常堆栈/日志定位并生成补丁 | workspace 文件检索 + 回滚保障 | 已实现 | 常见异常正确定位到源码行并给出合理修复 |
| **P2-2** | 自动化重构助手 ✅ | 批量替换、API 迁移、依赖升级 | Plan Mode（P1-1）+ 回滚（P0-1） | 已实现 | 简单批量替换 100% 按预期完成 |
| **P2-3** | 沙箱管理页面 ✅ | 会话沙箱状态查看、资源监控、手动清理 | `admin.sandbox.*` 配置体系 | 已实现 | 可查看并清理运行中的沙箱容器 |
| **P3-1** | 多 Agent 协作编程 | 产品/架构/开发/测试/Review Agent 协同 | 需 starter 侧 SubAgent/Pipeline 编排能力先行 | 周级 | 简单需求走完全流程产出可编译代码 |
| **P3-2** | 代码知识库问答 | 基于 RAG 的代码语义检索 | 需 starter 侧真实 Embedding RAG 先行（现为关键词版） | 周级 | Top-3 命中率 ≥ 70%，回答带出处 |

**P3 两项的前置依赖说明**：多 Agent 协作依赖 SubAgent/Pipeline 编排、知识库问答依赖真实 Embedding 向量检索，
这两块是 `customer-work-starter` 的既定演进方向。在 starter 能力
就绪前，admin-server 侧不启动这两项，避免在业务模块里重复造框架轮子。

---

## 4. 详细功能需求

### 4.1 会话一键回滚（P0-1）

#### 4.1.1 背景与依托

`GitWorkspaceService` 已在每次 `stream()` 写入前对会话 workspace 建立 git baseline（空提交），
"撤销本次会话全部修改"本质上就是 `git checkout baseline -- . && git clean -fd`，地基已完全就位。
这是所有 AI 改代码场景的信任底座：**有回滚，才敢放手让 Agent 改**。

#### 4.1.2 需求

1. **会话级回滚（本期范围）**
   - 接口：`POST /api/workspace/{agentCode}/vibecoding/rollback`，请求体 `{ "sessionId": "..." }`。
   - 行为：将会话 workspace 恢复到 baseline 状态（已跟踪文件 checkout，新增未跟踪文件清理），
     `.git` 目录本身保留。
   - 返回：`{ "restoredFiles": [...], "deletedFiles": [...] }`，前端刷新文件树与"本轮变更"时间线。
2. **前端交互**
   - VibeCoding 面板"本轮变更"时间线顶部新增"撤销全部修改"按钮，二次确认后调用。
   - 回滚成功后时间线清空、文件树刷新，并在对话流中插入一条系统提示。
3. **轮次级回滚（增强项，可延后）**
   - 每轮对话结束后自动 `git commit` 一次（message 带轮次号），支持"回滚到第 N 轮之后"。
   - 本期只做会话级，轮次级待实际使用中确有需求再排。

#### 4.1.3 约束

- 复用 `GitWorkspaceService` 的 `ProcessBuilder` 调 git 方式与错误码体系（`GIT_COMMAND_FAILED`）。
- 回滚是破坏性操作，接口幂等（重复调用恢复到同一 baseline），日志记录操作人、sessionId、恢复文件数。
- local 与 Docker 模式均支持：P1-3 bind mount 让容器会话目录实时同步到宿主机，会话 git 仓库建在宿主机
  会话目录，回滚对该目录执行 git 还原，两种模式语义一致（Docker 支持已随 P1-3 落地）。

#### 4.1.4 验收标准

- Agent 修改/新增/删除多个文件后，点击撤销，≤3s 全部恢复到对话前状态，文件树与磁盘一致。
- 回滚后再次对话可正常建立新变更（baseline 不被破坏）。

---

### 4.2 AI Code Review（P0-2）

#### 4.2.1 背景与依托

`GitWorkspaceService.diffAgainstBaseline()` 已能拿到本轮完整 diff；`GitAssistantService` 已验证
"一次性模型调用（不经 ReAct 工具循环）+ 独立线程池 + 超时兜底"的模式。Review 就是同一模式的第四个应用：
输入 diff，输出结构化审查意见。

#### 4.2.2 需求

1. **入口**
   - 接口：`POST /api/workspace/{agentCode}/vibecoding/review`，请求体 `{ "sessionId": "..." }`。
   - 前端：Git 助手抽屉内新增"Review 本次变更"标签页（与 diff 摘要/commit message/PR description 并列）。
2. **审查范围与规则**
   - 输入为本轮 diff（相对 baseline），diff 为空时返回 `NO_FILE_CHANGES` 错误码（复用现有）。
   - 系统提示词内置团队规范：NPE 防护、SQL 注入、硬编码密钥、异常处理、命名规范、日志规范
     （info/error、英文文案、错误码占位符）等。
   - 规范后续可通过 RAG 注入团队文档（依赖 P3-2，本期先内置在 prompt）。
3. **输出结构化 JSON**
   ```json
   {
     "issues": [
       {
         "severity": "CRITICAL|WARNING|SUGGESTION",
         "file": "src/main/java/...",
         "line": 42,
         "category": "SECURITY|PERFORMANCE|READABILITY|BUG|STYLE",
         "message": "...",
         "suggestion": "..."
       }
     ],
     "summary": "..."
   }
   ```
4. **前端展示**
   - 按严重级别分组列表展示；点击 file 定位到工作区文件查看器（文件读取接口已有）。
   - "一键生成修复"按钮：把选中的 CRITICAL/WARNING 意见拼成用户消息发回 `stream` 对话，
     由 Agent 修复（修复本身走既有 VibeCoding 链路，天然带 file_change 与回滚保障）。

#### 4.2.3 约束

- 模型输出 JSON 解析失败时降级：原文以 `summary` 返回、`issues` 为空（HTTP 200，不报错），不让前端拿到裸异常；
  AI 硬失败（模型空响应/调用异常/超时）统一返回专属错误码 `AI_REVIEW_FAILED(40019)`（实现时调整：不复用
  `GIT_ASSISTANT_AI_FAILED`，同一接口的全部 AI 失败对外只有一个码）。
- 模型输出的 `severity`/`category` 在后端统一大写归一并校验合法集合（唯一防御点）：未知 severity 兜底
  `SUGGESTION`、未知 category 兜底 `STYLE`，避免非规范值导致意见在前端分组中静默丢失。
- 超大 diff（如 > 100KB）截断处理并在 summary 中声明"仅审查前 N 个文件"。

#### 4.2.4 验收标准

- 对包含 NPE、SQL 注入、硬编码密码等典型问题的 diff，CRITICAL 检出率 ≥ 90%。
- Review 输出符合上述 Schema，前端可正常解析展示；"一键生成修复"能触发新一轮对话完成修复。

---

### 4.3 生成→沙箱验证→修复闭环（P0-3）

#### 4.3.1 背景与依托

沙箱执行链路已端到端跑通（实测 `write_file` → `javac Hello.java && java Hello` → `Exit code: 0`），
且 prompt 已引导 Agent"生成代码后在沙箱内 javac/mvn test 验证"。当前缺口：**验证结果混在文本流里，
前端无法结构化呈现，失败后是否修复完全靠 Agent 自觉**。本项是沙箱价值的最终变现，收口后
"需求→代码→单测→验证→修复"全程免人工介入。

#### 4.3.2 需求

1. **结构化 `test_report` 事件**
   - `VibeCodingService.stream()` 在检测到沙箱内执行编译/测试命令的 `TOOL_RESULT` 时
     （识别 `javac`/`mvn test`/`mvn compile` 等命令特征 + Exit code），解析结果并以独立 SSE 事件推送：
     ```
     event: test_report
     data: {"command":"mvn test","exitCode":0,"passed":12,"failed":0,"durationMs":8500,"failureDetails":[]}
     ```
   - 解析失败时降级为原始输出透传（`rawOutput` 字段），不阻断主流程。
2. **失败自动修复循环**
   - prompt 升级：明确要求"测试失败时分析失败原因并修复，最多重试 3 轮；3 轮后仍失败则停止并总结失败原因"。
   - 每轮修复产生的文件变更照常走 `file_change` 事件。
3. **单测生成约定**
   - Agent 生成主代码后自动生成 JUnit 5 / Mockito 测试用例，覆盖正常路径与异常路径（prompt 约定，沿用现有引导）。
   - 需求不明确时 Agent 在对话中先反问澄清（现有对话能力天然支持，不新增交互组件）。
4. **前端渲染**
   - 对话流中以"测试报告卡片"渲染 `test_report`：通过绿/失败红、展开可见失败明细。
   - 多轮修复时按时间线依次展示各轮报告。

#### 4.3.3 约束

- 沙箱执行超时沿用 `admin.sandbox.execute-timeout-seconds`（默认 60s），`mvn test` 场景实测偏慢时
  允许按 agent 粒度调大，但不全局放开。
- Docker 模式（network=none）下 `mvn test` 需依赖预热镜像内的本地仓库缓存。`test_report` 结构化解析对
  local/docker 一致启用（解析的是命令输出文本，两种模式都随流回传）；Docker 产物经 P1-3 bind mount 实时
  落宿主机，报告引用的文件与宿主机文件树对齐——Docker 模式已随 P1-3 验证通过。

#### 4.3.4 验收标准

- 对简单 CRUD 需求，生成代码可编译通过，单测覆盖主流程，运行成功率 ≥ 80%。
- 测试结果 100% 以 `test_report` 事件结构化返回；注入一个必然失败的用例，Agent 在 ≤3 轮内修复或明确报告失败原因。

---

### 4.4 Plan Mode 人工确认闭环（P1-1）

#### 4.4.1 背景与依托

现状：`PermissionMode` 为 BYPASS，危险命令靠 `SandboxGuardMiddleware` 正则拦截兜底（简单可靠但一刀切，
被拦截的命令 Agent 无法申请人工放行）。本项将其升级为框架级 HITL：高风险操作先出计划、等用户确认再执行。
starter 侧已有 Approval Store SPI 模式（接口 + InMemory 默认 + Jdbc 实现）可参考，但 admin-server
排除了 starter 自动装配，需在自己的 `@Configuration` 里显式装配。

#### 4.4.2 需求

1. **确认闭环（核心难点）**
   - `ChatService`/`VibeCodingService` 实现"流中暂停等确认"：Agent 计划执行高风险操作时，
     emit `plan` 事件后挂起等待，用户确认后恢复执行，拒绝则取消该操作并让 Agent 调整方案。
   - 接口：`POST /api/workspace/{agentCode}/vibecoding/plan/confirm`，请求体
     `{ "sessionId": "...", "planId": "...", "approved": true|false }`。
   - 确认等待需有超时（默认 5 分钟），超时视为拒绝，流正常结束而非挂死。
2. **高风险操作清单**
   - 删除文件；单轮批量修改 > 3 个文件；执行非只读命令（`mvn clean`、`rm` 等）；修改依赖版本（pom.xml 等）。
3. **`plan` SSE 事件**
   ```
   event: plan
   data: {"planId":"...","actions":[{"type":"DELETE","target":"src/..."}],"reason":"...","requiresConfirmation":true}
   ```
4. **前端**：对话流中渲染"计划确认卡片"（操作类型、目标文件、预期效果），确认/拒绝按钮。
5. **与现有拦截的关系**：`SandboxGuardMiddleware` 保留为最后防线（确认闭环故障时仍有兜底），
   两者叠加而非替换。

#### 4.4.3 技术风险提示

- SSE 长连接挂起等确认涉及连接保活（心跳事件）与服务重启后的恢复策略，需在方案设计时明确
  "挂起态不持久化、重启即取消"的简化边界，避免过度设计。
- 沿用 `Flux.defer` 模式处理 `HarnessAgent.stream()` 的同步异常（见第 2 章约束 2）。

#### 4.4.4 验收标准

- Plan Mode 开启后，Agent 删除文件前必须停下等确认；拒绝后文件未被删除且对话正常继续。
- 确认超时后流正常结束，不出现前端永久"生成中"。

---

### 4.5 交互式运行面板（P1-2）

> **实现结果**：`SandboxCommandService` 维护会话级交互式运行时。local 固定工作目录为会话 workspace；
> docker 直接复用生产 `DockerFilesystemSpec` 的镜像、资源、网络和 bind mount，再通过受控
> `docker exec` 逐块转发输出，规避框架 `Sandbox#exec` 完成后才返回的限制。用户命令在创建进程前复用
> `SandboxRiskDetector`，危险命令 fail-closed；命令历史由前端按会话保留最近 20 条。

#### 4.5.1 目标

让开发者在 VibeCoding 页面内直接对会话沙箱执行命令（编译、跑测试、运行程序），输出流式回显，
免去"页面看代码、切终端跑命令"的割裂。

#### 4.5.2 需求

1. **命令执行接口**
   - `POST /api/workspace/{agentCode}/vibecoding/execute`（SSE），请求体 `{ "sessionId": "...", "command": "mvn test" }`。
   - 命令在会话对应的沙箱内执行（local/docker 跟随 `admin.sandbox.mode`），复用现有执行链路与超时配置。
   - **必须过 `SandboxGuardMiddleware` 同一套危险命令校验**（用户手输的命令风险不低于 Agent 生成的）。
2. **前端"运行"面板**
   - VibeCoding 面板新增"运行"标签：命令输入框 + 常用命令快捷按钮（`javac`、`mvn compile`、`mvn test`）+
     终端风格输出区（等宽字体、保留 ANSI 颜色可后置）。
   - 执行历史保留本会话最近 20 条。
3. **输出事件**：复用/对齐 `test_report` 的解析逻辑——命令属于编译/测试类时同步产出结构化报告。

#### 4.5.3 验收标准

- 页面输入 `mvn test` 可看到流式输出与最终 Exit code；危险命令被拦截并提示。

---

### 4.6 Docker 沙箱补齐（P1-3）✅ 已实现

> **实现结果（P1-3）**：采用**方案 A（bind mount，挂 agent 工作区根）**。
> `AdminAgentInstanceFactory#buildDockerFilesystemSpec` 经框架 `DockerFilesystemSpec.additionalRunArgs`
> 注入 `docker run -v <宿主机 {agentCode}/ 绝对路径>:/workspace:rw`，容器工作区根与宿主机 agent 工作区根
> 双向实时可见。挂根而非只挂 `sessions/` 的原因：框架 MEMORY.md / Plan Mode `plans/` 写入走 Filesystem
> 抽象即容器 workspace 根（反编译 `WorkspaceManager#appendUtf8WorkspaceRelative`、`PlanModeManager#writePlan`
> 确认），挂根后这些跨会话文件同样落宿主机持久化、不随容器丢失。同时注入 `--user <宿主机 uid:gid>`
> （类加载时 `id -u`/`id -g` 探测一次，失败自动跳过并记日志）：根治原生 Linux Docker 上容器 root 写出
> 产物归 root:root、宿主机侧回滚（git checkout/clean）与文件保存撞属主的问题；并显式 `HOME=/workspace`
> 兼容 `--user` 下无 passwd 条目的 mvn 等进程。关闭框架默认 workspace projection（快照式拷贝，与挂载
> 重叠冗余）。Agent 被系统提示约束写入 `sessions/{sessionId}/`，经 bind mount 实时落宿主机，故
> file_change / 文件树 / Git 助手 / 回滚（P0-1）/ Review（P0-2）/ test_report（P0-3）在 Docker 模式
> **无需改造自动可用**——原先这些功能里"仅 local 模式"的门控已全部放开。容器内会话间可见面与 local 模式
> 等同（local 的 workspace 本就是 agent 根），HTTP 侧 sessionId 穿越防御不受影响。方案 B（docker cp）
> 曾作为过渡实现，bind mount 落地后已移除。`DockerSandboxIntegrationTest` 门控式集成测试**直接消费生产
> 工厂装配**（真起容器验证写文件→编译运行→读回、产物/MEMORY.md 宿主机实时可见、`--user` 属主对齐，及
> sessionId 转义 / 破坏性命令识别 / 资源获取失败快速抛错）本机 Docker 环境实测 4 用例全绿。

#### 4.6.1 背景

Docker 模式对话+执行链路已跑通，但产物文件在容器内，宿主机侧文件树 / file_change / Git 助手 / 回滚全部失效
（隔离的固有特性，非 bug）。生产环境要用 Docker 模式，这条腿必须补齐。

#### 4.6.2 需求

1. **容器↔宿主机产物同步**
   - 方案 A（优先评估）：容器启动时将会话 workspace 目录以 bind mount 挂载，产物直接落宿主机——
     若框架 `DockerFilesystemSpec` 支持挂载配置则成本最低。
   - 方案 B（兜底）：每轮 `TOOL_RESULT` 后通过 `docker cp` / tar 流增量同步容器内 workspace 到宿主机。
   - 同步落地后，file_change / Git 助手 / 回滚（P0-1）/ Review（P0-2）在 Docker 模式下自动可用，无需改造。
2. **`DockerSandboxIntegrationTest`（遗留待办）**
   - 门控式集成测试（本机无 Docker 时自动跳过，对齐现有 MySQL/Redis/Nacos 门控测试约定）。
   - 覆盖：容器内写文件→执行→读回、sessionId 转义（`SandboxSafeAgentStateStore`）、危险命令拦截、
     沙箱资源获取失败时 SSE 正常报错不挂起（回归第 2 章约束 2 的坑）。
3. **资源回收**：会话结束/超时后容器清理策略（对接 P2-3 沙箱管理页面的数据基础）。

#### 4.6.3 验收标准

- Docker 模式下：Agent 写文件后宿主机文件树可见、file_change 正常推送、Git 助手可生成 diff 摘要。
- 集成测试在有 Docker 的环境全绿，无 Docker 环境自动跳过。

---

### 4.7 智能 Bug 修复 / 日志诊断（P2-1）

> **实现结果**：新增 `/diagnose` SSE。日志按不可信数据定界，专用任务提示词要求先解析业务堆栈帧、
> 再检索源码和上下游；实际文件修改仍委托 `VibeCodingService`，因此自动获得 git baseline、
> `file_change`、`test_report`、回滚与 Review 能力，并追加 `DIAGNOSE` 专项审计。

#### 4.7.1 目标

Agent 根据异常堆栈、应用日志自动定位问题并生成修复补丁。

#### 4.7.2 需求

1. **输入方式**：用户粘贴异常堆栈到对话窗口（首版）；后续可对接日志平台按时间范围拉取。
2. **诊断流程**：解析堆栈定位类和方法 → workspace 中检索相关源码 → 分析根因 → 生成修复。
3. **输出**：根因分析说明 + 修复代码（走既有 VibeCoding 文件写入，天然带 file_change / 回滚 / Review 保障）
   +（可选）覆盖该异常场景的单元测试（联动 P0-3 验证闭环）。
4. **入口**：对话命令 `/diagnose` 或独立接口 `POST /api/workspace/{agentCode}/vibecoding/diagnose`。

#### 4.7.3 验收标准

- 对常见 NullPointerException、IndexOutOfBoundsException、SQL 语法错误，能正确定位到源码行并给出合理修复；
  修复后走 P0-3 闭环验证通过。

---

### 4.8 自动化重构助手（P2-2）

> **实现结果**：新增 `/refactor` SSE 与四种 `RefactorTask.TaskType`。服务端在调用 Agent 前先创建任务级
> `plan` 并真实挂起；批准后用 `accept_edits` 模式执行普通编辑，命令、删除、依赖修改仍保留细粒度二次确认。
> 拒绝/超时不调用修改流，全部任务落 `REFACTOR` 专项审计并复用统一回滚基线。

#### 4.8.1 目标

支持大规模、可审计、可回滚的代码重构任务。**强依赖 P1-1（Plan Mode）与 P0-1（回滚）先行**——
没有确认与回滚保障的批量改动不允许上线。

#### 4.8.2 需求

1. **重构任务类型**：批量替换（统一异常类/返回封装）、API 迁移（替换废弃 API/升级 SDK）、
   依赖升级（版本升级/漏洞修复）、代码风格统一（统一日志框架/常量命名）。
2. **执行流程**：
   - Step 1：Plan Mode 输出重构计划（影响文件数、变更点、风险）。
   - Step 2：用户确认后执行，每完成一个文件 emit `file_change`。
   - Step 3：执行完成后走 P0-3 闭环编译/测试，反馈 `test_report`。
3. **审计**：记录重构日志（操作人、时间、变更文件、结果）；回滚复用 P0-1。

#### 4.8.3 验收标准

- 简单批量替换任务 100% 按预期完成；升级依赖后项目在沙箱内编译通过。

---

### 4.9 沙箱管理页面（P2-3）

> **实现结果**：开发工具抽屉内提供沙箱管理页，展示当前用户/智能体下的交互式会话沙箱、容器 ID、
> 状态、CPU/内存实测值和生效配置，支持幂等停止/删除。Docker 容器空闲到期后在下一次执行或查询时回收；
> local 模式只管理活跃宿主进程，不伪造容器指标。

#### 4.9.1 目标

为运维/开发提供沙箱运行态的可见性与管理能力（Docker 模式为主）。

#### 4.9.2 需求

1. 会话沙箱列表：sessionId、模式、容器 ID、状态、创建时间、资源占用。
2. 手动清理：停止并删除指定会话的沙箱容器（联动 P1-3 的资源回收策略）。
3. 配置可视化：当前 `admin.sandbox.*` 生效配置只读展示（含 env 覆盖后的实际值）。

#### 4.9.3 验收标准

- 可查看运行中的沙箱并手动清理；清理后再次对话能正常重建沙箱。

---

### 4.10 多 Agent 协作编程（P3-1）

> **降级版已交付 ✅**（完整形态待 starter 演进）。
> **完整形态前置依赖**：starter 侧 SubAgent / Pipeline 编排能力（`SubAgentProvider`、`SequentialPipeline` 等）
> 支持产品/架构/开发/测试/Review 五角色可暂停恢复、可中途介入。该依赖未就绪。
>
> **降级实现**：复用项目自研的顺序编排思路（参照 starter `MultiAgentOrchestrator#sequential`：各角色输出作为
> 下一角色输入逐步细化），在 admin-server 落 `CollaborativeCodingService`。VibeCoding 面板新增"协作模式"开关
> （默认关）；开启后一次需求输入走 **需求分析 → 方案设计 → 编码实现 → 自测审查** 顺序流水（角色数量/提示词
> 可配 `admin.collaboration.*`，默认 4 角色）。各角色边界经 SSE `role_stage` 事件推送；**编码角色产出走既有
> VibeCoding 沙箱/file_change/test_report 链路**（编排层不另起写入通道）；某角色失败 **fast fail 中断流水**并明确
> 报错（错误码 40025），审计埋点 `COLLAB_STREAM`。
>
> **与完整形态的差异**：① 4 个内置角色（合并"测试"进编码角色的自测 + "审查"角色），非五角色独立体；
> ② 顺序流水不支持"中途暂停/修改某步输出后继续"（无 P1-1 暂停恢复接入）；③ 角色间只传文本上下文
> （分析/设计）与 git diff（审查），未做结构化 PRD/接口契约产物物件化。

#### 4.10.1 角色设计

| 角色 | 职责 | 输出 |
|---|---|---|
| 产品经理 Agent | 理解用户需求，输出 PRD / 用户故事 | PRD 文档 |
| 架构师 Agent | 设计接口、数据模型、模块划分 | 设计文档 / 接口契约 |
| 开发 Agent | 根据设计写代码 | 代码文件 |
| 测试 Agent | 写单测、集成测试 | 测试用例 |
| Reviewer Agent | Code Review | Review 意见（复用 P0-2 结构） |

#### 4.10.2 执行流程

1. 用户输入原始需求，编排器按"产品经理 → 架构师 → 开发 → 测试 → Reviewer"顺序调度，
   每个 Agent 的输出作为下一个的输入。
2. 最终输出：PRD、设计文档、代码、测试、Review 意见汇总；代码/测试环节复用 P0-3 闭环验证。
3. 用户可中途介入，修改某一步的输出后继续（依赖 P1-1 的暂停/恢复交互基础）。

#### 4.10.3 验收标准

- 对简单需求（如"新增一个用户管理 CRUD 接口"），走完完整流程并产出沙箱内可编译代码。

---

### 4.11 代码知识库问答（P3-2）

> **降级版已交付 ✅**（完整形态待 starter 演进）。
> **完整形态前置依赖**：starter 侧真实向量检索（接真向量数据库、增量/租户隔离的语义检索）。
>
> **降级实现**：用 **DashScope 真实 Embedding**（`text-embedding-v3`，走既有 `ai_model_config` 模型配置体系拿
> Key）+ MySQL 向量存储（新表 `ai_code_knowledge_index` / `ai_code_knowledge_chunk`，Flyway V26）+ **应用层
> 余弦相似度** 实现语义检索与检索增强问答。落 `KnowledgeService`：对指定源码目录按 **类/方法级** 切块（`CodeChunker`）
> → Embedding → 入库；`/knowledge/search`（语义 top-k）+ `/knowledge/ask`（RAG 问答，带出处）。索引构建 **显式触发、
> 进度可查**（索引行 status + chunk_count）；源码路径受 `admin.knowledge.allowed-roots` 白名单约束；Embedding Key
> 缺失 **fast fail**（错误码 40027，不静默降级回关键词）。前端入口放工作区抽屉。
>
> **与完整形态的差异**：① 向量存 MySQL（JSON 数组）、相似度在应用层算，数据量万级以内合理，升级路径是接
> 真向量库（Milvus/pgvector）；② 索引显式触发、不做文件变更自动监听（无增量热更）；③ 引用来源标注到
> 文件 + 符号（类/方法名），未精确到行号范围；④ 尚未接入"对话 @知识库 / 生成前自动检索 / Review 规范 RAG 注入"。

#### 4.11.1 需求

1. **代码库向量化**：扫描 workspace 或指定仓库源码，按类/方法/注释切块，Embedding 后入向量库，
   按用户/租户隔离，支持增量更新。
2. **问答能力**：自然语言 → 相关代码片段 → 自然语言回答，回答标注引用来源（文件路径 + 行号范围）。
   示例："这段业务逻辑在哪实现的？""这个接口有哪些调用方？"
3. **与对话集成**：VibeCoding/Chat 面板 @知识库 提问；Agent 生成代码前自动检索知识库做参考；
   Review（P0-2）的团队规范注入切换为 RAG 来源。

#### 4.11.2 验收标准

- 常见接口定位问题 Top-3 检索命中率 ≥ 70%；回答带出处。

---

## 5. 非功能需求

### 5.1 性能
- SSE 流首包响应时间 ≤ 2s（模型首 token 到达时间除外）。
- file_change 事件从文件变更到前端展示 ≤ 1s（✅ 已达成）。
- 会话回滚 ≤ 3s；Review 单次 ≤ 30s（超时兜底沿用 `GitAssistantService` 模式）。

### 5.2 安全
- 所有代码/命令执行必须经沙箱（local 受 workspace 路径约束，docker 受容器隔离 + network=none）。
- `SandboxGuardMiddleware` 危险命令拦截默认开启，对 Agent 生成命令与用户手输命令（P1-2）一视同仁。
- Agent 侧不做真正的 git commit/push；未来若放开，写操作必须人工确认 + 审计日志。
- 回滚、计划确认等破坏性/敏感接口记录审计日志（操作人、时间、目标、结果）。

### 5.3 可观测性
- 所有 AI 编码操作记录审计日志（操作人、时间、变更文件、模型调用 token 数）。
- 关键路径（Review、验证闭环、回滚、重构）输出结构化日志；日志遵循项目规范
  （info/error、英文文案、错误码占位符）。

### 5.4 可回滚
- P0-1 落地后，"撤销本次会话所有修改"成为所有写操作类功能的统一兜底，不再各功能自建备份目录
  （v1.0 的 `.ai-backup/{timestamp}/` 方案废弃，统一走 git baseline）。

### 5.5 兼容性
- 不影响现有 Chat / VibeCoding 核心流程；新功能通过配置开关控制，默认关闭（沙箱 guard 除外，默认开启）。
- 新增 SSE 事件类型对旧前端向后兼容（未知事件忽略不报错）。

---

## 6. 接口设计

### 6.1 后端接口

| 方法 | 路径（前缀 `/api/workspace/{agentCode}/vibecoding`） | 说明 | 状态 |
|---|---|---|---|
| POST | `/stream` | 流式对话（含 file_change 事件） | ✅ 已实现 |
| GET | `/sandbox-mode` | 查询当前沙箱模式 | ✅ 已实现 |
| GET | `/artifacts` | 会话产物清单 | ✅ 已实现 |
| GET | `/files` | 会话文件树 | ✅ 已实现 |
| GET | `/file-content` | 读文件内容 | ✅ 已实现 |
| PUT | `/file-content` | 写文件内容 | ✅ 已实现 |
| GET | `/git-diff` | 本轮 diff 摘要 | ✅ 已实现 |
| POST | `/commit-message` | 生成 commit message | ✅ 已实现 |
| POST | `/pr-description` | 生成 PR description | ✅ 已实现 |
| POST | `/rollback` | 撤销本次会话全部修改 | 🎯 P0-1 |
| POST | `/review` | 对本轮 diff 做 Code Review | ✅ 已实现 P0-2 |
| POST | `/plan/confirm` | Plan Mode 计划确认/拒绝 | ✅ 已实现 P1-1 |
| POST | `/execute` | 交互式沙箱命令执行（SSE） | ✅ 已实现 P1-2 |
| POST | `/diagnose` | 根据堆栈/日志诊断 Bug | ✅ 已实现 P2-1 |
| POST | `/refactor` | 执行自动化重构任务 | ✅ 已实现 P2-2 |
| GET | `/sandbox/config`、`/sandbox/sessions` | 生效配置/沙箱列表 | ✅ 已实现 P2-3 |
| DELETE | `/sandbox/sessions/{sessionId}` | 停止并清理会话沙箱 | ✅ 已实现 P2-3 |
| POST | `/knowledge/ingest`、`/knowledge/query` | 代码库向量化/问答 | 🎯 P3-2 |

### 6.2 SSE 事件类型

| event | data 示例 | 说明 | 状态 |
|---|---|---|---|
| `message` | `{text}` | 可见回答正文 | ✅ 已实现 |
| `node:*`（reasoning/tool_* 等） | `{text}` | 思考过程/工具执行节点（`ChatNodeKind` 派生） | ✅ 已实现 |
| `file_change` | `{"operation":"MODIFY","path":"...","description":"..."}` | 文件变更事件（CREATE/MODIFY/DELETE） | ✅ 已实现 |
| `done` | `[DONE]` | 流结束 | ✅ 已实现 |
| `test_report` | `{"command":"mvn test","exitCode":0,"passed":12,"failed":0,"round":1,"exhausted":false,...}` | 沙箱编译/测试结构化报告（含 round/exhausted 修复轮次进度） | ✅ 已实现 P0-3（local 与 docker 均启用，docker 产物经 P1-3 bind mount 同步） |
| `plan` | `{"planId":"...","actions":[{"type":"DELETE","target":"..."}],"reason":"...","requiresConfirmation":true,"timeoutSeconds":300}` | Plan Mode 高风险操作待确认，流挂起 | ✅ 已实现 P1-1 |
| `plan_result` | `{"planId":"...","status":"APPROVED\|REJECTED\|TIMEOUT"}` | 计划终态通知（超时=服务端自动拒绝，前端据此停倒计时） | ✅ 已实现 P1-1 |
| `command_output` | `{"stream":"combined","text":"...","timestamp":...}` | 交互式命令实时输出块 | ✅ 已实现 P1-2 |
| `command_result` | `{"exitCode":0,"success":true,"durationMs":1200,...}` | 交互式命令唯一终态 | ✅ 已实现 P1-2 |
| `command_error` | `{"code":40044,"message":"..."}` | 命令门禁/运行时失败的可展示错误 | ✅ 已实现 P1-2 |
| `review_result` | `{issues, summary}` | Review 结果 | ⛔ 不采用：Review 走同步接口 `POST /review` 返回，未落 SSE 事件（见 §6.1） |

---

## 7. 数据模型

### 7.1 FileChangeEvent（✅ 已实现，以实际代码为准）
```java
// file_change SSE 事件的 data 载荷；无 RENAME/oldPath
public record FileChangeEvent(String operation, String path, String description) {
    // operation: CREATE | MODIFY | DELETE
}
```

### 7.2 ReviewIssue（✅ 已实现 P0-2，以实际代码为准）
```java
public record ReviewIssue(
    String severity,    // CRITICAL|WARNING|SUGGESTION
    String file,
    Integer line,
    String category,    // SECURITY|PERFORMANCE|READABILITY|BUG|STYLE
    String message,
    String suggestion
) {}
```

### 7.3 TestReport（✅ 已实现 P0-3，以实际代码为准）
```java
public record TestReport(
    String command,          // 识别出的命令：mvn test | mvn | javac
    Integer exitCode,        // 进程退出码，解析不到为 null
    boolean success,         // 综合判定（exitCode==0 且无失败用例）
    int passed,
    int failed,              // Failures + Errors
    int skipped,
    Long durationMs,         // 解析不到为 null
    List<String> failureDetails,
    String rawOutput,        // 解析降级时透传的原始输出尾段（成功且完整解析时为 null）
    int round,               // 本轮对话内第几次编译/测试执行（1 基）
    boolean exhausted        // 是否已达失败自动修复轮数上限（仍失败）
) {}
```
> 与初版设计相比新增 `success`/`skipped`/`round`/`exhausted` 四个字段，承载失败自动修复循环
> 的进度可视化（P0-3 需求 2）；`passed`/`failed` 由 `Integer` 收敛为原生 `int`（缺省 0，语义更清晰）。

### 7.4 RefactorTask（🎯 P2-2）
```java
public record RefactorTask(
    String taskType,     // REPLACE|API_MIGRATION|DEPENDENCY_UPGRADE|STYLE
    String description,
    List<String> targetFiles,
    boolean requiresConfirmation
) {}
```

---

## 8. 实施路线图

### 阶段一：P0 收口——信任与闭环（1~1.5 周）
- 会话一键回滚（P0-1）：半天出接口，联调前端按钮。
- AI Code Review（P0-2）：复用 GitAssistantService 模式 + 前端 Review 标签页。
- 生成→验证→修复闭环（P0-3）：`test_report` 事件 + prompt 升级 + 测试报告卡片。
- **出口标准**：日常 VibeCoding 使用中，"改错可撤销、质量有 Review、对错自动验"三件事全部页面内完成。

### 阶段二：P1 升级——HITL 与生产化（2~3 周）
- Plan Mode 人工确认闭环（P1-1）：先做方案设计评审（SSE 挂起边界），再实现。
- 交互式运行面板（P1-2）。
- ✅ Docker 沙箱补齐（P1-3）：已采用 bind mount 方案（框架原生 `WorkspaceSpec`+`BindMountEntry`）；
  `DockerSandboxIntegrationTest` 已补齐并实测通过。

### 阶段三：P2 扩展——诊断与批量操作（2~3 周）
- 智能 Bug 修复/日志诊断（P2-1）。
- 自动化重构助手（P2-2，依赖阶段二的 Plan Mode）。
- 沙箱管理页面（P2-3）。

### 阶段四：P3 远期——协作与知识库（依赖 starter 演进，不设时限）
- starter 侧先行：SubAgent/Pipeline 编排验证、真实 Embedding RAG。
- 多 Agent 协作编程（P3-1）、代码知识库问答（P3-2）。

---

## 9. 风险与应对

| 风险 | 影响 | 应对措施 |
|---|---|---|
| Agent 生成代码质量不稳定 | 高 | P0 三件套（回滚 + Review + 验证闭环）构成三重校验，均为本版最高优先级 |
| SSE 挂起等确认导致连接泄漏/前端假死 | 高 | P1-1 明确"挂起不持久化、超时即拒绝、重启即取消"边界；沿用 `Flux.defer` 兜底同步异常（一期实测坑） |
| ~~Docker 模式产物不可见导致功能"看似失效"~~ | 已消除 | P1-3 bind mount 同步机制已落地：容器 `/workspace/sessions` 实时挂到宿主机会话目录，依赖宿主机文件的功能（回滚/Review/Git 助手/test_report）Docker 模式与 local 等价可用 |
| 自动化重构误改大量文件 | 高 | 强制 Plan Mode 确认（P1-1 先行）+ git baseline 回滚（P0-1 先行）+ 分批执行 |
| 沙箱执行 `mvn test` 超时/离线仓库缺依赖 | 中 | local 模式先行验收；Docker 镜像预热本地仓库；超时按 agent 粒度可调 |
| 代码库向量化成本高 | 中 | P3 延后启动；增量更新、按租户隔离、缓存 Embedding |
| Git 写操作安全风险 | 高 | 维持现状：Agent 侧只读 diff，不做真实 commit/push；未来放开必须人工确认 + 审计 |

---

## 10. 附录

### 10.1 相关代码入口（admin-server 实际类名）

- `VibeCodingService` —— VibeCoding 业务入口（流式对话 + file_change 增量快照）
- `VibeCodingController` —— 接口层（`/api/workspace/{agentCode}/vibecoding/**`）
- `GitWorkspaceService` —— 会话 git baseline / diff（P0-1 回滚在此扩展）
- `GitAssistantService` —— 一次性模型调用生成 Git 文本（P0-2 Review 复用此模式）
- `AdminAgentInstanceFactory` —— HarnessAgent 装配（沙箱模式挂载、P1-1 PermissionMode 切换点）
- `AdminSandboxProperties` / `SandboxGuardMiddleware` / `SandboxSafeAgentStateStore` —— 沙箱配置/命令拦截/Docker sessionId 转义
- `ChatService` —— 通用流式对话（`Flux.defer` 同步异常兜底模式）
- `ChatNodeKind` —— SSE 事件类型枚举（新增事件类型在此扩展）

> 注意：admin-server 排除了 starter 的自动装配（`spring.autoconfigure.exclude`），需要 starter 能力时在
> 自己的 `@Configuration` 里显式 new——文档中凡引用 starter 类（如 Approval Store SPI）均指"参考其模式"，
> 不是假设容器里有现成 Bean。

### 10.2 关键配置项（实际生效前缀为 `admin.sandbox.*`）

```yaml
admin:
  sandbox:
    mode: ${ADMIN_SANDBOX_MODE:local}            # local | docker
    execute-timeout-seconds: ${ADMIN_SANDBOX_EXECUTE_TIMEOUT_SECONDS:60}
    permission-mode: ${ADMIN_SANDBOX_PERMISSION_MODE:bypass}  # bypass（默认，护栏静默改写兜底）| hitl（P1-1 高风险挂起等人工确认）
    docker:
      image: ${ADMIN_SANDBOX_DOCKER_IMAGE:maven:3.9-eclipse-temurin-17}
      memory-mb: ${ADMIN_SANDBOX_DOCKER_MEMORY_MB:512}
      cpu-count: ${ADMIN_SANDBOX_DOCKER_CPU_COUNT:1}
      network: ${ADMIN_SANDBOX_DOCKER_NETWORK:none}
    guard:
      enabled: ${ADMIN_SANDBOX_GUARD_ENABLED:true}
    hitl:                                          # 仅 permission-mode=hitl 时生效（P1-1）
      confirm-timeout-seconds: ${ADMIN_SANDBOX_HITL_CONFIRM_TIMEOUT_SECONDS:300}  # 确认超时，超时按拒绝
      batch-modify-threshold: ${ADMIN_SANDBOX_HITL_BATCH_MODIFY_THRESHOLD:3}      # 单轮批量修改超此值需确认
    features:                                      # P1/P2 增量能力默认全部关闭，按项灰度
      command-execution-enabled: ${ADMIN_SANDBOX_COMMAND_EXECUTION_ENABLED:false}
      diagnosis-enabled: ${ADMIN_SANDBOX_DIAGNOSIS_ENABLED:false}
      refactor-enabled: ${ADMIN_SANDBOX_REFACTOR_ENABLED:false}
      management-enabled: ${ADMIN_SANDBOX_MANAGEMENT_ENABLED:false}
      idle-timeout-minutes: ${ADMIN_SANDBOX_IDLE_TIMEOUT_MINUTES:30}
```

> **Plan Mode HITL（P1-1）**：`permission-mode=hitl` 时，vibecoding 会话中 Agent 计划执行高风险操作
> （删除文件 / 执行非只读或破坏性命令 `rm`·`mvn clean` 等 / 修改 pom.xml 等依赖文件 / 单轮批量修改超阈值）
> 会先 emit `plan` SSE 事件并**在流中挂起**，前端弹确认卡片，用户批准后恢复执行、拒绝或超时（默认 5min）
> 则取消该操作让 Agent 调整方案，流正常继续。挂起态只存内存、**服务重启即失效**（重启后确认接口 fast fail）。
> 默认 `bypass` 保持现状（不影响非 vibecoding 链路），`SandboxGuardMiddleware` 始终作为最后防线叠加兜底：
> **catastrophic 级命令（如 `rm -rf`、触碰 `.git`、访问 `/etc` `/root`）即使 HITL 人工批准，仍会被护栏
> 最终改写拦截——护栏是不可绕过的最后防线（设计意图，非缺陷）**；人工批准真正放行的是 HITL 独有的
> 确认级操作（`mvn clean`、修改依赖文件、批量修改等）。

新功能的配置项按同一约定扩展（如 `admin.vibecoding.review.*`、`admin.vibecoding.plan-mode.*`），
均支持 env 覆盖，默认值保守（新功能默认关闭）。

### 10.3 参考文档

- AgentScope Java 官方文档 https://java.agentscope.io （Harness / Sandbox / Plan Mode / Subagent）

---

*文档结束*
