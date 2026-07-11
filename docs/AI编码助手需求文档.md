# 智能体客服后台管理系统 —— AI 编码助手需求文档

> 基于 AgentScope 2.0（2.0.0 GA，`ga2.0` 分支）与现有 `customer-work-spring-boot-starter` 能力进行二次开发。  
> 版本：v1.0  
> 目标读者：产品、后端/前端开发、测试  
> 状态：待评审

---

## 1. 背景与目标

### 1.1 背景
当前系统已在 `customer-admin-server` 中实现基础的 **VibeCoding** 能力：用户可在智能体工作区通过自然语言让 Agent 读/写 `workspace` 目录下的代码文件，并在对话结束后通过"前后快照对比"返回变更文件清单。但该能力仍处于"一期降级方案"阶段，缺少实时反馈、与 Git 工作流打通、代码审查等关键能力。

### 1.2 目标
在不改动 AgentScope 2.0 核心框架的前提下，基于已有能力（`ChatService`、`VibeCodingService`、`HarnessAgentFactory`、`MultiAgentOrchestrator`、`KnowledgeProvider`、`MCP`、`Sandbox`、`Plan Mode`、`Subagent`）构建一套面向开发团队的 **AI 编码助手**，覆盖：代码生成、代码审查、Bug 诊断、自动化重构、多 Agent 协作编程、Git 工作流集成、代码知识库问答。

### 1.3 成功指标
- VibeCoding 流式输出中 **file_change 事件实时可见**，延迟 < 1s。
- 代码审查 Agent 对典型 Java 代码 diff 给出可行动的评论（覆盖率 ≥ 80% 的常见规范项）。
- Git 工作流集成后，单次代码变更可自动生成 commit message / PR description，人工修改率 < 30%。
- 代码知识库问答：Top-3 检索命中率 ≥ 70%（以内部接口定位任务评估）。

---

## 2. 功能需求总览

| 优先级 | 功能模块 | 核心能力 | 依赖 | 验收标准 |
|---|---|---|---|---|
| P0 | 实时 file_change 事件 | VibeCoding 文件变更实时流式推送 | ChatService、SSE | 文件创建/修改/删除即时出现在对话流 |
| P0 | Git 工作流集成 | git diff 摘要、自动生成 commit message / PR description | Git MCP / JGit | 可一键复制或自动填充 |
| P1 | Sandbox + Plan Mode | 代码执行隔离、重大修改前计划确认 | HarnessAgentFactory | 危险操作需用户确认 |
| P1 | AI Code Review | 对 diff 做自动 Review | RAG + Subagent | 输出结构化 Review 意见 |
| P2 | 智能 Bug 修复 | 根据异常堆栈/日志定位并生成补丁 | DiagnosticService | 生成可应用的 patch |
| P2 | 代码生成 + 单测生成 | 需求→代码→单测→运行反馈 | VibeCoding + Sandbox | 单测可运行并反馈结果 |
| P2 | 自动化重构助手 | 批量替换、API 迁移、依赖升级 | Plan Mode + Skill | 按计划执行并产出 diff |
| P3 | 多 Agent 协作编程 | 产品/架构/开发/测试/Review Agent 协同 | MultiAgentOrchestrator | 完成端到端需求实现 |
| P3 | 代码知识库问答 | 基于 RAG 的代码语义检索 | KnowledgeProvider | 接口/逻辑定位准确 |

---

## 3. 详细功能需求

### 3.1 实时 file_change 事件（P0）

#### 3.1.1 现状
`VibeCodingService#stream` 仅返回 `ChatStreamChunk`（reasoning/message），文件变更在对话结束后通过 `listChangedArtifacts` 用快照对比得出，用户无法在对话过程中看到 Agent 正在改哪些文件。

#### 3.1.2 需求
1. **事件类型扩展**：在 `ChatStreamChunk` 中新增 `file_change` 类型，字段包括：
   - `type`: `file_change`
   - `operation`: `CREATE | MODIFY | DELETE | RENAME`
   - `path`: 相对 workspace 的路径
   - `oldPath`: RENAME 时原路径（可选）
   - `description`: 变更摘要（Agent 生成的一句话说明）
2. **采集机制**：
   - 方式 A（推荐）：Hook AgentScope 的 `FileChangeEvent`（若框架暴露）或在 Agent 调用文件工具时拦截。
   - 方式 B（兜底）：在 `stream` 开始后启动一个后台线程，每 500ms 扫描一次 workspace 目录，与初始快照对比，发现变更即 emit `file_change` 事件。
3. **前端渲染**：
   - 在对话流右侧或折叠面板中显示"文件变更时间线"。
   - 支持点击文件路径查看 diff（调用后端的 git diff 或文件对比接口）。

#### 3.1.3 接口
```
POST /api/workspace/{agentCode}/vibecoding/stream
响应 SSE event:
  event: message          data: {text}
  event: reasoning        data: {text}
  event: file_change      data: {"operation":"MODIFY","path":"src/main/java/...","description":"修改了登录校验逻辑"}
  event: done             data: [DONE]
```

#### 3.1.4 验收标准
- 用户说"把 UserService 的密码校验改成 BCrypt"，Agent 修改文件后 1s 内前端显示该文件变更。
- 变更时间线按时间顺序排列，不遗漏、不重复。

---

### 3.2 Git 工作流集成（P0）

#### 3.2.1 目标
让 AI 编码助手能感知当前代码变更，并辅助生成 Git 相关文本，减少开发者在提交环节的重复劳动。

#### 3.2.2 需求
1. **Git Diff 摘要**
   - 后端提供接口 `GET /api/workspace/{agentCode}/vibecoding/git-diff?sessionId=xxx`。
   - 对当前 workspace 执行 `git diff`，将 diff 文本传给 Agent，要求其用 1-3 句话总结变更内容。
   - 返回结构化结果：`{ "summary": "...", "changedFiles": [...] }`。
2. **自动生成 Commit Message**
   - 接口：`POST /api/workspace/{agentCode}/vibecoding/commit-message`
   - 请求体：`{ "sessionId": "...", "style": "conventional" | "simple" }`
   - 返回：`{ "message": "feat(auth): 切换密码校验为 BCrypt" }`
   - 默认采用 Conventional Commits 规范，可通过配置切换风格。
3. **自动生成 PR Description**
   - 接口：`POST /api/workspace/{agentCode}/vibecoding/pr-description`
   - 返回 Markdown 格式 PR 描述，包含：变更摘要、改动文件清单、影响范围、自检清单。
4. **前端集成**
   - 在 VibeCoding 面板新增"Git 助手"抽屉：显示 diff 摘要、commit message、PR description，支持一键复制。
   - （可选）提供"自动执行 git add + git commit"按钮，需二次确认。

#### 3.2.3 技术实现
- 后端使用 **JGit** 或直接调用 `git` 命令读取 diff。
- 接入 **Git MCP**（如果已有）或自定义 `GitTool`：
  - `git_diff(workspace)`
  - `git_status(workspace)`
  - `git_commit(workspace, message)`
  - `git_push(...)`
- 将 diff 文本作为 user message 的一部分发送给 Agent，prompt 模板示例：
  ```
  请根据以下 git diff 生成一条符合 Conventional Commits 规范的 commit message，
  要求：不超过 72 个字符的标题 + 详细描述。
  diff:
  {diff}
  ```

#### 3.2.4 验收标准
- 对一次真实代码变更，生成的 commit message 人工无需修改即可直接使用的比例 ≥ 50%。
- PR description 包含变更摘要、文件清单、影响范围三个部分。

---

### 3.3 Sandbox + Plan Mode（P1）

#### 3.3.1 目标
让 Agent 的代码执行更安全，并在执行重大修改前给出可确认的计划。

#### 3.3.2 需求
1. **Sandbox 配置开启**
   - 默认使用 `local` 沙箱（限制在 workspace 目录内）。
   - 生产环境可切换为 `docker` 沙箱，配置镜像、CPU/内存限制、网络策略。
   - 沙箱内执行命令/代码时，禁止访问 `/etc`、`/root`、数据库连接等敏感资源。
2. **Plan Mode 开启**
   - 配置：`customer-work.harness.plan-mode.enabled=true`
   - 当 Agent 计划执行以下高风险操作时，先输出计划并等待用户确认：
     - 删除文件
     - 批量修改多个文件（>3 个）
     - 执行非只读命令（如 `mvn clean`、`rm -rf`）
     - 修改依赖版本（pom.xml、package.json 等）
   - 计划内容包含：操作类型、目标文件、预期效果、回滚方式。
   - 前端在流中显示"计划确认卡片"，用户点击"确认"后 Agent 才继续执行。
3. **回滚机制**
   - 在执行高风险操作前，自动对目标文件做备份（拷贝到 `.ai-backup/{timestamp}/`）。
   - 提供"撤销本次对话所有修改"按钮，恢复备份。

#### 3.3.3 验收标准
- 在 Plan Mode 下，Agent 删除文件前必须停下来等待用户确认。
- 误操作后可一键撤销，文件恢复到对话前状态。

---

### 3.4 AI Code Review（P1）

#### 3.4.1 目标
利用子智能体对代码 diff 进行自动化审查，输出结构化、可行动的 Review 意见。

#### 3.4.2 需求
1. **入口**
   - 工作区对话命令：`/review` 或 VibeCoding 面板"Review 本次变更"按钮。
   - 接口：`POST /api/workspace/{agentCode}/vibecoding/review`
2. **审查范围**
   - 以当前 workspace 的 git diff 为输入。
   - 可加载团队代码规范文档（通过 RAG 注入）。
3. **Reviewer Agent（Subagent）**
   - 角色：资深 Java 工程师 / 代码规范守门员。
   - 系统提示词包含团队规范（如：禁止 SQL 注入、NPE 防护、异常处理、命名规范、单元测试要求等）。
   - 输出结构化 JSON：
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
   - 以列表形式展示 Review 意见，按严重级别分组。
   - 点击意见可定位到代码文件对应行（若信息足够）。
   - 提供"一键生成修复"按钮，让 Agent 针对 CRITICAL/WARNING 项自动生成补丁。

#### 3.4.3 验收标准
- 对包含 NPE、SQL 注入、硬编码密码等典型问题的代码 diff，CRITICAL 问题检出率 ≥ 90%。
- Review 输出符合上述 JSON Schema，前端可正常解析展示。

---

### 3.5 智能 Bug 修复 / 日志诊断（P2）

#### 3.5.1 目标
让 Agent 能根据异常堆栈、应用日志自动定位问题并生成修复补丁。

#### 3.5.2 需求
1. **输入方式**
   - 用户粘贴异常堆栈到对话窗口。
   - 用户选择时间范围，后端从日志文件/日志平台拉取相关日志。
2. **诊断流程**
   - Agent 解析堆栈，定位异常发生的类和方法。
   - 在 workspace 中查找相关源码。
   - 结合 RAG 中的历史修复记录/知识库，分析根因。
   - 生成修复建议或补丁（patch 格式）。
3. **输出**
   - 根因分析说明。
   - 修复后的代码片段或 patch 文件。
   - （可选）自动生成单元测试覆盖该异常场景。
4. **人工确认**
   - 修复补丁需经用户确认后应用，应用前自动备份原文件。

#### 3.5.3 验收标准
- 对常见 NullPointerException、IndexOutOfBoundsException、SQL 语法错误等，能正确定位到源码行并给出合理修复。

---

### 3.6 代码生成 + 单元测试生成（P2）

#### 3.6.1 目标
根据自然语言需求生成可运行的代码，并同步生成单元测试。

#### 3.6.2 需求
1. **需求→代码**
   - 用户在 VibeCoding 面板输入需求文本，或者上传md文档、或者txt文件，如"实现一个基于 JWT 的登录接口"。
   - Agent 自动分析需求，规划文件结构，生成代码，未明确的需求需要在页面提示人工确认后，明确需求后继续生成代码。
   - 代码生成的文件采用 sessions/{sessionId}/ 方式存储，便于后续查询和管理。
2. **同步生成单测**
   - Agent 生成主代码后，自动识别需要测试的类/方法，保证编译通过。
   - 生成 JUnit 5 / Mockito 测试用例，覆盖正常路径和异常路径。
3. **运行反馈**
   - 在 Sandbox 中执行 `mvn test`。
   - 将测试结果（通过/失败、失败原因）流式返回给用户。
   - 若测试失败，Agent 自动尝试修复（最多 3 轮）。
4. **输出格式**
   - `file_change` 事件实时展示生成的文件。
   - 测试报告以 `test_report` 事件返回。

#### 3.6.3 验收标准
- 对简单 CRUD 接口，生成代码可编译通过。
- 生成的单测全部覆盖主流程，运行成功率 ≥ 80%。

---

### 3.7 自动化重构助手（P2）

#### 3.7.1 目标
支持大规模、可审计、可回滚的代码重构任务。

#### 3.7.2 需求
1. **重构任务类型**
   - 批量替换：如统一异常类、统一返回结果封装。
   - API 迁移：如替换废弃的 API、升级 SDK。
   - 依赖升级：如 Spring Boot 版本升级、依赖漏洞修复。
   - 代码风格统一：如统一日志框架、统一常量命名。
2. **执行流程**
   - Step 1：Plan Mode 输出重构计划（影响文件数、变更点、风险）。
   - Step 2：用户确认后执行。
   - Step 3：每完成一个文件 emit `file_change` 事件。
   - Step 4：执行完成后运行编译/测试，反馈结果。
3. **回滚与审计**
   - 自动备份所有被修改文件。
   - 记录重构日志（操作人、时间、变更文件、结果）。

#### 3.7.3 验收标准
- 对简单的批量替换任务（如替换某个工具类调用），100% 按预期完成。
- 升级依赖后项目可编译通过。

---

### 3.8 多 Agent 协作编程（P3）

#### 3.8.1 目标
模拟完整软件研发流程，让多个专家 Agent 协同完成需求。

#### 3.8.2 角色设计
| 角色 | 职责 | 输出 |
|---|---|---|
| 产品经理 Agent | 理解用户需求，输出 PRD / 用户故事 | PRD 文档 |
| 架构师 Agent | 设计接口、数据模型、模块划分 | 设计文档 / 接口契约 |
| 开发 Agent | 根据设计写代码 | 代码文件 |
| 测试 Agent | 写单测、集成测试 | 测试用例 |
| Reviewer Agent | Code Review | Review 意见 |

#### 3.8.3 执行流程
1. 用户输入原始需求。
2. `MultiAgentOrchestrator` 按顺序调度各 Agent：
   - 产品经理 → 架构师 → 开发 → 测试 → Reviewer。
   - 每个 Agent 的输出作为下一个 Agent 的输入。
3. 最终输出：PRD、设计文档、代码、测试、Review 意见汇总。
4. 用户可中途介入，修改某一步的输出后继续。

#### 3.8.4 验收标准
- 对简单需求（如"新增一个用户管理 CRUD 接口"），能走完完整流程并产出可编译代码。

---

### 3.9 代码知识库问答（P3）

#### 3.9.1 目标
让开发者通过自然语言查询私有代码库，快速定位业务逻辑和调用关系。

#### 3.9.2 需求
1. **代码库向量化**
   - 扫描 workspace 或指定仓库的源码文件。
   - 按类、方法、注释切块，使用 Embedding 模型生成向量。
   - 存入向量数据库（RAG  Provider 已支持 bailian/dify/simple 等）。
2. **问答能力**
   - 接口： natural language → 相关代码片段 → 自然语言回答。
   - 示例问题：
     - "这段业务逻辑在哪实现的？"
     - "这个接口有哪些调用方？"
     - "UserService 的登录逻辑是怎么做的？"
3. **与对话集成**
   - 在 VibeCoding/Chat 面板中，用户可 @知识库 提问。
   - Agent 在生成代码前可自动检索相关知识库做参考。

#### 3.9.3 验收标准
- 对常见接口定位问题，Top-3 检索命中率 ≥ 70%。
- 回答中需标注引用来源（文件路径 + 行号范围）。

---

## 4. 非功能需求

### 4.1 性能
- SSE 流首包响应时间 ≤ 2s（模型首 token 到达时间除外）。
- file_change 事件从文件变更到前端展示 ≤ 1s。
- 代码库向量化处理 1000 个文件 ≤ 10 分钟。

### 4.2 安全
- 所有代码执行必须在 Sandbox 内进行，禁止访问宿主机敏感路径。
- Agent 调用 Git 写操作（commit/push）前必须人工确认。
- 对代码库向量化的数据做访问控制，按用户/租户隔离。

### 4.3 可观测性
- 所有 AI 编码操作记录审计日志（操作人、时间、变更文件、模型调用 token 数）。
- 关键路径（代码生成、Review、重构）输出结构化日志，便于排查。

### 4.4 可回滚
- 每次高风险操作前自动备份。
- 提供"撤销本次对话所有修改"能力。

### 4.5 兼容性
- 不影响现有 Chat / VibeCoding 核心流程。
- 新功能通过配置开关控制，默认关闭，避免对未启用用户产生副作用。

---

## 5. 接口设计（新增/调整）

### 5.1 后端接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/workspace/{agentCode}/vibecoding/stream` | 已有，扩展 `file_change` 事件 |
| GET | `/api/workspace/{agentCode}/vibecoding/git-diff` | 获取当前 workspace git diff |
| POST | `/api/workspace/{agentCode}/vibecoding/commit-message` | 生成 commit message |
| POST | `/api/workspace/{agentCode}/vibecoding/pr-description` | 生成 PR description |
| POST | `/api/workspace/{agentCode}/vibecoding/review` | 对当前 diff 做 Code Review |
| POST | `/api/workspace/{agentCode}/vibecoding/diagnose` | 根据堆栈/日志诊断 Bug |
| POST | `/api/workspace/{agentCode}/vibecoding/generate-tests` | 为指定类生成单元测试 |
| POST | `/api/workspace/{agentCode}/vibecoding/refactor` | 执行自动化重构任务 |
| POST | `/api/workspace/{agentCode}/vibecoding/rollback` | 撤销本次对话所有修改 |
| POST | `/api/workspace/{agentCode}/knowledge/ingest` | 代码库向量化入库 |
| POST | `/api/workspace/{agentCode}/knowledge/query` | 代码知识库问答 |

### 5.2 SSE 事件类型

| event | data 示例 | 说明 |
|---|---|---|
| `reasoning` | `{text}` | 思考过程 |
| `message` | `{text}` | 可见回答正文 |
| `file_change` | `{operation, path, description}` | 文件变更事件 |
| `test_report` | `{passed, failed, details}` | 测试报告 |
| `plan` | `{actions, requiresConfirmation}` | Plan Mode 计划确认 |
| `review_result` | `{issues, summary}` | Review 结果 |
| `done` | `[DONE]` | 流结束 |

---

## 6. 数据模型

### 6.1 ChatStreamChunk（扩展）
```java
public record ChatStreamChunk(
    boolean reasoning,    // 是否思考过程
    String text,          // 文本内容
    FileChange fileChange // 新增：文件变更信息
) {}

public record FileChange(
    String operation,
    String path,
    String oldPath,
    String description
) {}
```

### 6.2 ReviewIssue
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

### 6.3 RefactorTask
```java
public record RefactorTask(
    String taskType,     // REPLACE|API_MIGRATION|DEPENDENCY_UPGRADE|STYLE
    String description,
    List<String> targetFiles,
    boolean requiresConfirmation
) {}
```

---

## 7. 实施路线图

### 阶段一：基础体验增强（2 周）
- 实现实时 `file_change` 事件（方式 B 兜底）。
- Git diff 摘要、commit message、PR description 生成。
- 前端 VibeCoding 面板新增"Git 助手"抽屉。

### 阶段二：安全与审查（2 周）
- 开启 Sandbox + Plan Mode。
- 实现自动备份与撤销能力。
- 搭建 Reviewer Subagent，实现 AI Code Review。

### 阶段三：智能诊断与生成（3 周）
- 智能 Bug 修复 / 日志诊断。
- 代码生成 + 单元测试生成 + 测试运行反馈。
- 自动化重构助手。

### 阶段四：高级协作（4 周）
- 多 Agent 协作编程。
- 代码知识库问答（向量化 + 检索）。
- 效果评估与迭代优化。

---

## 8. 风险与应对

| 风险 | 影响 | 应对措施 |
|---|---|---|
| Agent 生成代码质量不稳定 | 高 | 引入 Plan Mode、Review Agent、单测运行反馈三重校验 |
| 自动化重构误改大量文件 | 高 | 强制 Plan Mode 确认、自动备份、分批执行 |
| Sandbox 性能开销大 | 中 | 默认 local 沙箱，Docker 沙箱按环境开启 |
| 代码库向量化成本高 | 中 | 增量更新、按租户/项目隔离、缓存 Embedding |
| Git 写操作安全风险 | 高 | 所有写操作必须人工确认，记录审计日志 |

---

## 9. 附录

### 9.1 相关代码入口
- `VibeCodingService.java` —— VibeCoding 业务入口
- `ChatService.java` —— 流式对话服务
- `HarnessAgentFactory.java` —— Sandbox / Subagent / Plan Mode 配置
- `MultiAgentOrchestrator.java` —— 多 Agent 编排
- `KnowledgeProvider.java` —— RAG 知识检索
- `SessionConfig.java` —— AgentStateStore 持久化配置

### 9.2 关键配置项
```yaml
customer-work:
  harness:
    plan-mode:
      enabled: true
    sandbox:
      mode: local   # local | docker
    subagent:
      enabled: true
  skill:
    runtime-load-tool-enabled: true
    code-execution-enabled: true
```

---

*文档结束*
