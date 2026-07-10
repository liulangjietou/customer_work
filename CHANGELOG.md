# Changelog

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/) 与 [Keep a Changelog](https://keepachangelog.com/zh-CN/)。

## [Unreleased]

### AgentScope Java 2.0.0-RC4 → 2.0.0 GA 升级（新建 `ga2.0` 分支，`rc2.0` 冻结为历史存档）

- **背景**：AgentScope Java 2.0.0 GA 于 2026-07-10 发布（经 5 个 RC 迭代）。新建 `ga2.0` 分支承接升级，
  `rc2.0` 分支本身不改动。
- **验证方法**：用 `git worktree` 拉取 upstream 仓库 RC4 tag 与 v2.0.0 tag 源码逐行 diff（而非只读 release
  notes），确认本项目实际调用的核心 API 面（`EventType`/`PermissionMode`/`MiddlewareBase`/`McpClientWrapper`/
  `StreamOptions`/`ThinkingBlock`/`MysqlAgentStateStore` 等）零改动，`AgentBase`/`ReActAgent`/`HarnessAgent`
  仅内部响应式接线变化（不影响未覆写受保护方法的用法）。
- **唯一破坏性改动**：内置模型实现（`AnthropicChatModel`/`DashScopeChatModel`/`GeminiChatModel`/
  `OllamaChatModel`/`OpenAIChatModel`）自 GA 起从 `agentscope-core` 拆分为独立的
  `agentscope-extensions-model-{provider}` 模块，包名 `io.agentscope.core.model.*` → 
  `io.agentscope.extensions.model.{provider}.*`（Builder API 本身零变化）。改动 4 个文件的 import 语句
  （`ModelConfig.java`/`ModelConfigTest.java`/`BailianIntegrationTest.java`/`AdminModelFactory.java`）+
  `pom.xml`/`customer-work-spring-boot-starter/pom.xml`（`agentscope.version=2.0.0` + 新增 5 个模型扩展依赖，
  版本交由 `agentscope-bom` 统一管理）。
- **已修复的已知缺陷**：框架内置 `fallbackModel` 的 bug（[#1850](https://github.com/agentscope-ai/agentscope-java/issues/1850)）
  已由 [#1851](https://github.com/agentscope-ai/agentscope-java/pull/1851) 于 2026-07-06 修复并合入 GA。
- **验证结果**：全仓 `mvn clean test` **全绿**（starter 362 + app 13 + downstream 1 + customer-web 8 +
  `customer-admin-server` 127 = 511，0 失败 0 错误，1 跳过为需真实 API Key 的联调测试）；第二节固化的两个
  P0 探针回归测试重跑全绿，框架行为未漂移。
- **顺带修复的 2 个既有 bug（与 AgentScope 升级无关，此前被本机数据库凭据不匹配这个更表层的错误一直遮蔽）**：
  1. `CustomerWorkProperties.java` 拼装 JDBC URL 时把 `characterEncoding` 设成 `utf8mb4`（MySQL 侧字符集名），
     但该参数要的是 **Java NIO Charset 名**，Connector/J 无法识别直接连接失败；改为 `UTF-8`（表级
     `DEFAULT CHARSET=utf8mb4` 不受影响，仍在各 `CREATE TABLE` 语句里）。
  2. `JdbcAuditSink` 的 `QUERY_BY_SESSION_SQL` 里 `ESCAPE '\\'`（Java 源码里只有一个反斜杠）在实际 SQL 文本里
     是未正确转义的单个反斜杠，MySQL 把它解析成转义下一个字符（右单引号），导致字符串字面量未正常闭合、
     吞掉了后面的 `LIMIT ?` 占位符，报 `Parameter index out of range`；改为 `ESCAPE '\\\\'`（SQL 文本层面
     两个反斜杠，表示一个转义后的字面反斜杠字符）。
  3. **本机开发环境凭据对齐**（非代码改动）：本机 MySQL root 密码由 `rootpassword` 改为测试代码硬编码期望的
     `root`（starter 模块多个 `Jdbc*Test` 无环境变量覆盖机制）；本机 Redis 设置 `requirepass=123456`
     （匹配 `RedisSessionPersistenceTest`）。**副作用**：`customer-admin-server` 默认配置
     （`ADMIN_MYSQL_PASSWORD` 默认值 `rootpassword`）随之失配，运行其测试/应用需显式
     `export ADMIN_MYSQL_PASSWORD=root`。
- **issue 全量重新核对（本次任务的核心工作）**：逐一核对文档原引用的 29 个 issue 在 GA 下的当前状态，确认
  3 个已修复合入 GA（[#1850](https://github.com/agentscope-ai/agentscope-java/issues/1850) fallbackModel、
  [#1979](https://github.com/agentscope-ai/agentscope-java/issues/1979) fat-jar 下 ClasspathSkillRepository、
  [#1968](https://github.com/agentscope-ai/agentscope-java/issues/1968) 中断后状态未保存，均已核实修复 PR
  合并时间早于 GA 发布），其余 26 个仍 open；另对仓库当前 402 个 open issues（RC4 时点 120 个）中 RC4 后
  新提交的部分做关键词相关性筛选，新发现 3 条参考价值风险（[#1988](https://github.com/agentscope-ai/agentscope-java/issues/1988)/[#1989](https://github.com/agentscope-ai/agentscope-java/issues/1989) 中间件内
  `interrupt()` 跨 session 静默失效、[#1906](https://github.com/agentscope-ai/agentscope-java/issues/1906)
  skill 目录伴生文件 bug、[#2075](https://github.com/agentscope-ai/agentscope-java/issues/2075) MCP SDK
  安全漏洞待修）。**子智能体相关 4 个已知问题**（#1954/#1953/#1700/#1911）按指示本轮不处理，维持既有规避方案。
- 详见 [docs/MIGRATION-2.0.md](docs/MIGRATION-2.0.md) 「9. RC4 → GA 升级」「10. GA issue 全量重新核对」两节，
  及更新后的 [docs/生产就绪评估.md](docs/生产就绪评估.md)。

### 客服后台运营管理系统（批次六：体验补强，10 项需求全部完成）

- **MCP 支持 http 传输 + 连通性测试**：`ai_mcp.mcp_type` 新增 `http`；`AdminMcpFactory`（新组件）统一
  构建 `McpClientBuilder`，`AdminAgentInstanceFactory` 收敛复用；`McpService#testConnectivity` 复用
  模型连通性测试同一套异步线程池 + 硬超时模式，落库新增的 `test_status`/`test_time` 字段。
- **Skill 支持上传 SKILL.md / zip**：`SkillService#parseUploadContent` 按扩展名分流，zip 用
  `ZipInputStream` 解出 `SKILL.md` 正文；明确只是 `content` 字段的另一种录入方式，不支持多文件技能包。
- **智能体图标库选择 + 新建 session + 工作区空状态**：`IconPicker.vue` 复用 Element Plus 全局图标集；
  聊天/VibeCoding 面板新增"新建会话"按钮；`workspace` 路由新增静态空状态页替换原来的 404。
- **对话历史持久化（重启不丢）**：`AgentStateStore` 从 `InMemoryAgentStateStore` 换成
  `MysqlAgentStateStore`，新表 `ai_chat_session_state`。已用独立脚本直接验证 save/get/listSessionIds
  全链路。
- **聊天/VibeCoding 流式区分思考过程与正文**：`ChatService#chatStream` 返回 `Flux<ChatStreamChunk>`
  （按 `Event.getType()` 打标），SSE 拆成 `reasoning`/`message` 两种 event；前端 `ThinkingBlock.vue`
  可折叠展示思考过程，`MarkdownRenderer.vue`（`markdown-it` + `highlight.js`）渲染表格与代码块。
- **历史对话列表**：新增 `GET /workspace/{agentCode}/chat/sessions` 与
  `.../sessions/{sessionId}/messages` 只读端点，前端 `ChatHistorySidebar.vue` 侧边栏。
- **视觉细节**：favicon 换成用户头像图；菜单节点无 `icon` 时用 `Folder`/`Document` 兜底；左上角新增
  `customer_work` logo。
- **历史对话列表 Redis 读缓存**（追加）：权威数据源仍是 `MysqlAgentStateStore`（写路径不变），新增
  `ChatHistoryCache` 给两个只读历史接口加 30 分钟 TTL 缓存降低 MySQL 读压力；一轮对话结束后主动
  `evict` 而不是被动等 TTL 过期，Redis 不可达时退化为直读 MySQL，不影响主流程。

### Fixed

- **深链接直接刷新页面必现 404**：`router/index.ts` 的动态路由注册守卫里，等 `menuStore.bootstrap()`
  注册完动态路由后用 `{ ...to, replace: true }` 重新触发导航——但 `to` 是路由注册前解析出来的，此时
  已经带着 `name: 'NotFound'`；Vue Router 对重定向目标只要带 `name` 字段就按具名路由优先解析（无视
  同时存在的 `path`），于是原样跳回 NotFound，表现就是任何非首页地址一刷新就 404。改成只传
  `{ path: to.fullPath, replace: true }`，强制按路径重新匹配，命中刚注册的路由。
- **MCP 连通性测试 config 只认平铺 JSON，贴 Claude/Cursor 标准格式（带 `mcpServers` 包装）会静默失败**：
  `AdminMcpFactory` 新增 `unwrapMcpServers`，两种格式自动识别。
- **MCP 连通性测试报 `"MCP client 'xxx' not initialized"`**：`McpClientBuilder.buildAsync()` 只构造
  客户端对象不建立连接，`listTools()` 前必须先 `initialize()` 完成握手，之前漏了这一步；真实注册路径
  （`Toolkit#registerMcpClient`）本身没问题。
- **`MysqlAgentStateStore` 库名硬编码，联调环境换库名就报"表不存在"**：`AdminAgentRuntimeConfig` 早期
  版本把库名字面量写死成 `"customer_admin"`，与 `spring.datasource.url` 实际指向的库脱节。改成从新增
  的 `admin.mysql.database-name` 配置项（`ADMIN_MYSQL_DATABASE` 环境变量覆盖）读取，与数据源配置同源。

- **模型连通性测试落库失败，前端只显示"系统繁忙"**：`ModelConfigService#testConnectivity` 的结果落库
  发生在独立线程池 `model-test-worker` 的 `CompletableFuture` 回调里，而不是发起请求的 Tomcat 线程；
  MyBatis-Plus 审计字段自动填充要调 `StpUtil.isLogin()`，脱离 Servlet 线程时 Sa-Token 不返回 `false`
  而是直接抛异常（`NotWebContextException`/`SaTokenContextException`，公共父类
  `SaTokenException`），导致整个 `updateById` 失败、真实的连通性测试结果（成功/超时/HTTP 错误）永远
  落不了库，前端只能看到语焉不详的兜底错误码。`MyMetaObjectHandler.currentUserId()` 捕获
  `SaTokenException` 返回 `null`（审计填充的唯一入口，一处兜底不用每个后台线程调用点都记得处理）。
  新增 `MyMetaObjectHandlerTest`（2，验证非 Web 上下文调用不抛异常）。

### 客服后台运营管理系统 customer-admin-web（批次五：前端 SPA，全部批次完成）

`customer-admin-web`：Vue 3 + TypeScript + Vite 独立 SPA（非 Maven 子模块，与仓库根目录平级），Element
Plus + Pinia + Vue Router + Axios。至此《客服项目后台管理系统需求文档 v1.0》五个批次全部交付。

- **脚手架**：`api/request.ts` 统一 axios 拦截器（Result 拆箱、10001 未登录/20002 强制改密两种错误码
  特殊跳转），`types/api.ts` 与后端全部 DTO/VO 字段严格对齐。
- **鉴权**：`store/auth.ts`（token/nickname/forceChangePassword/permissions 持久化到 localStorage）+
  登录页 + 强制改密页；后端新增 `GET /api/auth/permissions`（返回当前用户全量权限点，含按钮/接口级
  type=2，与菜单树接口只返回 type=1 菜单节点区分开），供前端 `v-permission` 指令用。
- **动态路由 + 菜单轮询**：登录后按 `GET /api/menu/routes` 返回的树用 `router.addRoute('Layout', ...)`
  运行时注册路由（静态叶子 path -> 组件映射表在 `router/component-map.ts`，动态智能体节点复用同一个
  `workspace/:agentCode` 通配路由，不逐个注册）；`store/menu.ts` 2s 轮询 `GET /api/menu/version`，版本
  变化才拉全量菜单树，智能体页面自身的 CRUD/启停操作后也会主动 `refreshMenu()`，两者叠加满足需求文档
  "菜单刷新≤1s"。
- **v-permission 指令**：没有对应权限点时直接从 DOM 移除元素（不是仅隐藏），前端只是体验层，真正裁决
  始终是后端 `@SaCheckPermission`。
- **系统管理三页 + AI 配置四页**：用户/角色（权限树勾选）/操作日志（只读）、模型配置（AppKey 脱敏输入框
  + 测试连通性按钮 + 默认模型开关）/ MCP（stdio/sse 两种 config JSON 占位符提示）/ Skill（SKILL.md 正文
  编辑）/ 智能体（模型下拉 + MCP·Skill 多选 + 能力勾选 + 启停按钮）。
- **智能体工作区**：`utils/sse.ts` 用 `fetch` + `ReadableStream` 手写 SSE 解析（不能用原生
  `EventSource`——聊天/VibeCoding 端点是 POST 且需要自定义 `Authorization` 头，原生 `EventSource` 只支持
  GET 且不能自定义头）；`ChatPanel.vue` 逐 token 渲染，`VibeCodingPanel.vue` 额外提供产物清单查看
  （对话结束后拉取一次变更文件列表）。
- **联调时发现并修复的真实 bug（非纸面走查能发现）**：Element Plus 的 `<el-form>` 渲染出原生 `<form>`
  标签，登录页/改密页原来只绑定 `@keyup.enter`、没有 `@submit.prevent`——密码框回车会同时触发浏览器
  原生表单提交（整页刷新）和 Vue 的 keyup 处理器。原生提交造成的整页刷新会让只存在 Pinia 内存里的
  `forceChangePassword` 状态丢失（localStorage 里 token 仍有效，但强制改密标记被重置为默认值
  `false`），相当于用户绕过强制改密门禁直接进入系统。修复两处：① 两个表单补 `@submit.prevent`；
  ② `forceChangePassword` 改为持久化到 localStorage（不再只活在内存里），双重兜底。
- 验证：`npm run build`（`vue-tsc -b && vite build`）通过；用浏览器工具走通登录 -> 强制改密页跳转 ->
  主布局动态菜单渲染 -> 7 个静态 CRUD 页面路由与后端 VO 字段一一对应的表头 全流程，过程中无控制台报错；
  后端侧 `customer-admin-server` 45 个单测保持全绿，全仓（含批次五新增的 `/api/auth/permissions`）
  `mvn test` 无回归。
- 文档：README §6.21 更新为"批次一~五全部完成"，新增前端要点与联调 bug 记录；代码结构树补充
  `customer-admin-server`/`customer-admin-web` 条目。

### 客服后台运营管理系统 customer-admin-server（新模块，批次一/五：后端骨架）

按《客服项目后台管理系统需求文档 v1.0》新增独立子模块，前后端分离，本批次交付后端 RBAC 骨架。

- **技术选型**：需求文档锁定 MyBatis-Plus 3.5.7 + Sa-Token 1.39.0（新引入依赖，与仓库其余模块的
  手写 JDBC Store SPI 模式并存互不冲突——14 张表带外键关系的 RBAC 系统更适合 ORM，不强行套用旧模式）。
  `spring-security-crypto` 仅取 `BCryptPasswordEncoder`，不引入完整 Spring Security 自动装配（避免与
  Sa-Token 鉴权流程冲突）。前端 `customer-admin-web` 独立 Vue3 SPA，后续批次接入。
- **模块接入手法**：照抄 `customer-web` 已验证的 WebFlux/MVC 混合排除模式（`spring-boot-starter-web` +
  `web-application-type: servlet` + `spring.autoconfigure.exclude: CustomerWorkAutoConfiguration`），
  为批次四"动态智能体运行时工厂"复用 `customer-work-spring-boot-starter` 的 Agent 构建能力预留接口。
- **RBAC 五表 + Flyway**：`sys_user`/`sys_role`/`sys_permission`（树形）/`sys_user_role`/`sys_role_permission`/
  `sys_operation_log`（登录日志合并存储，需求文档 §5 本就只列一张表）。Flyway `V1__init_schema.sql`/
  `V2__seed_data.sql` 仅本地/测试 profile 自动执行，生产 `flyway.enabled=false` 走 DBA 参照
  `mysql/admin-schema.sql` 手工执行（与仓库既有"生产不自动建表"约定一致，两库物理隔离）。
- **RBAC 落地**：`AdminStpInterfaceImpl`（`role_code=super_admin` 特判直接放行全部权限，不为超管冗余插入
  `sys_role_permission` 记录）+ `@SaCheckPermission` 接口级权限点校验 + `SaInterceptor` 全局登录态兜底。
- **登录闭环**：`admin/admin` 默认超管（BCrypt 哈希已本地校验 `matches("admin", hash)=true`），首次登录
  按"当前密码是否仍等于种子哈希值"判定 `forceChangePassword`（不额外加表字段）；登录失败也记操作日志
  （通用 `OperationLogAspect` 依赖已登录态解析操作人，覆盖不了失败场景，故登录走 `AuthService` 直接调用
  `OperationLogMapper` 记录，logout/改密走已登录路径复用通用 AOP 切面）。
- **AES 加密**：`AesGcmCryptoUtil`（JDK 内置 `javax.crypto`，IV 随机拼接密文自包含），仅
  `ai_model_config.api_key` 一个敏感字段，不做成通用字段级注解框架（过度抽象不划算）。
- **`system.user/role/permission/log` 四个业务域完整 CRUD**：统一 `PageQuery`/`PageResult`（服务端分页
  默认 10 条 + 名称搜索 + 状态筛选 + 创建时间排序）；角色权限树剪枝算法（保留自身被授权或有后代被授权的
  节点，标准"保留祖先路径"写法，避免前端因缺中间节点导致树渲染断裂）；超管角色不可编辑/删除。
- **静态菜单聚合**：`GET /api/menu/routes` 按当前用户权限点过滤 `sys_permission`（type=1）组装树；
  动态智能体节点与 `GET /api/menu/version` 版本号接口留到批次三（智能体管理落地后）接入。
- **统一响应/异常/错误码**：`Result<T>{code,message,data,timestamp}`，错误码按认证/权限/参数/外部依赖
  四段（1xxxx/2xxxx/3xxxx/4xxxx）分类，与 `customer-work-app` 现有的 `{status,error,message,requestId}`
  响应体规范不同、不混用（两套系统独立演进）。
- 新增 `AesGcmCryptoUtilTest`（6）、`SensitiveDataMaskerTest`（3）、`SeedAdminPasswordTest`（1，防止
  手改种子脚本导致默认超管登录不了的回归测试）、`CustomerAdminServerApplicationTests`（MySQL 门控完整
  上下文启动测试，已本地空跑验证 `assumeTrue` 在 Spring 上下文加载前正确拦截、不会在无 MySQL 环境报错）。
  全仓测试 +10，BUILD SUCCESS。
- 文档：README 新增 §6.21 模块说明与启动方式；`mysql/admin-schema.sql` 新增（14 张表 DDL + 种子数据，
  DBA 预审用，与 `mysql/schema.sql` 物理隔离）。

### 客服后台运营管理系统 customer-admin-server（批次二：AI 配置域三个 CRUD）

在批次一 RBAC 骨架基础上接入模型/MCP/Skill 三个 AI 配置域管理能力。

- **Skill / MCP 管理 CRUD**：与 `system.*` 四个业务域相同的分页/搜索/筛选/排序模式；MCP 新增
  `mcpType`（仅 `stdio`/`sse`）与 `config`（须为合法 JSON）的一处防御式校验（`create`/`update` 复用）。
- **模型配置 CRUD + AES 加密**：`ai_model_config.api_key` 用批次一已写好的 `AesGcmCryptoUtil` 加密落库，
  新建必填 apiKey、编辑留空则不改（复用 `UserService` 改密的"留空不覆盖"手法）；列表/详情接口只回显
  `apiKeyMasked`（解密后掩码末 4 位），真实密文/明文都不出现在响应体里。
- **默认模型互斥设置**：`@Transactional` 内，设为默认时先 `LambdaUpdateWrapper` 清空其余行的
  `is_default`，保证同一时刻至多一个默认模型（需求文档"可设置一个默认模型"）。
- **连通性测试（`AdminModelFactory`）**：JDK 内置 `java.net.http.HttpClient` 直连
  `{baseUrl}/chat/completions`（不引入三方 HTTP 客户端依赖），固定 prompt 探测，HTTP 2xx 且响应体含
  `choices` 数组判定成功，超时（8s，落在需求文档 5~10s 区间）/HTTP 错误/结构非法均判定失败并记录原因。
  超时时长做成构造参数（生产用无参构造走默认 8s，测试可注入短超时），避免单测真的等 8 秒。
- **不阻塞 Tomcat 请求线程**：`ModelConfigController#testConnectivity` 返回
  `CompletableFuture<Result<ModelTestResult>>`——Spring MVC 异步支持下，等待外部模型接口响应期间会释放
  发起请求的 Tomcat 线程；`ModelConfigService` 内部把实际探测派发到独立的 8 线程守护线程池
  （`model-test-worker`），与请求处理线程池物理隔离，`orTimeout` 做硬性截断兜底（对应实施计划 §五
  "模型测试超时 5~10s 不阻塞"）。
- 新增 `SkillService`/`McpService`/`ModelConfigService` 及对应 Controller，权限点 `skill:*`/`mcp:*`/
  `model:*`（连通性测试复用 `model:view`，不新增权限点，因为它不修改配置）。
- 测试：`ModelConfigServiceTest`（8，AppKey 加密落库非明文/编辑留空不覆盖/脱敏回显/默认模型互斥事务/
  连通性测试结果落库/未知 id 拒绝；MyBatis-Plus 纯 Mockito 单测首次用到 `LambdaUpdateWrapper`，需手动
  `TableInfoHelper.initTableInfo` 注册 lambda 缓存，否则报 "can not find lambda cache"——平时该缓存由
  Spring 容器启动扫描 Mapper 时自动注册，纯单测没有容器）、`AdminModelFactoryTest`（4，成功/结构非法/
  HTTP 错误/硬超时四种场景，用 JDK 内置 `com.sun.net.httpserver.HttpServer` 模拟 OpenAI 兼容端点，
  与 `AdminModelFactory` 本身"零额外依赖"的设计取舍保持一致，不引入 WireMock）。全仓测试 360，
  BUILD SUCCESS。
- 文档：README §6.21 更新为"批次一~二"进度，补充 AI 配置域三个入口说明。

### 客服后台运营管理系统 customer-admin-server（批次三：智能体管理 + 动态菜单）

在批次二 AI 配置域基础上接入智能体管理与动态菜单聚合。

- **`ai_agent` CRUD + 关联表整体替换式维护**：`AgentSaveRequest.modelId` 必填、`mcpIds`/`skillIds`
  可选多选；`AgentService` 校验 `modelId` 引用真实存在的模型、`mcpIds`/`skillIds`（若提供）逐个真实
  存在、`agentCode` 格式 `^[a-z0-9-]+$`（用于动态菜单路由）、`capabilities` 仅接受 `chat`/`vibecoding`
  （一处防御式校验，`create`/`update` 复用）。关联表 `ai_agent_mcp`/`ai_agent_skill` 采用"整体替换"
  （先删该智能体现有关联行，再按本次提交的 ids 批量插入）而非增量比对——关联行数很少，比对差异没有
  必要，`@Transactional` 保证原子性。
- **生命周期**：`PUT /api/aiconfig/agent/{id}/enable`/`disable` 独立于普通编辑，只改 `status` 字段。
- **跨域引用完整性**（补齐批次二遗留项，`ai_agent` 落地后才具备校验条件）：模型/MCP/Skill 的
  `delete()` 现在会检查是否被 `ai_agent`/`ai_agent_mcp`/`ai_agent_skill` 引用，命中则拒绝并返回新增的
  `RESOURCE_IN_USE(30005)`，避免删除后关联表出现悬挂行。
- **动态菜单聚合**：`MenuAggregationService` 在静态菜单剪枝树基础上，把 `AgentService.listEnabled()`
  的结果拼进 `permCode=workspace` 的节点下（找不到 workspace 节点——即当前用户没有该菜单权限——则
  跳过，动态节点天然继承父节点的可见性，不需要额外的按智能体权限过滤）。
- **`MenuVersionHolder`**：进程内 `AtomicLong`，智能体 CRUD/启停任一操作后自增；新增
  `GET /api/menu/version` 供前端轻量轮询，仅版本变化才拉全量 `GET /api/menu/routes`（需求文档"菜单
  刷新≤1s"：当前操作用户前端主动刷新即时生效，本机制兜底"其它在线用户下次轮询感知"；预留未来多实例
  部署换 Redis 实现，接口签名不变）。
- 权限点 `agent:*`（`view`/`add`/`edit`/`delete`，均已在批次一种子数据里预置）。
- 测试：`AgentServiceTest`（10，字段校验四种失败场景/关联表整体替换/生命周期启停/菜单版本联动/
  modelName 与关联 id 正确解析），`MenuAggregationServiceTest`（2，智能体动态节点正确拼接/workspace
  未授权时整个节点及其动态子节点均不可见；用 `Mockito.mockStatic(StpUtil.class)` 模拟静态方法，
  仓库内首次用到，Mockito 5.x 默认 inline mock maker 无需额外依赖）。全仓测试 394，BUILD SUCCESS。
- 文档：README §6.21 更新为"批次一~三"进度，补充智能体管理与动态菜单接口说明。

### 客服后台运营管理系统 customer-admin-server（批次四：智能体运行时）

在批次三智能体管理基础上接入动态运行时——本计划的架构核心。

- **`AdminAgentInstanceFactory`（动态智能体运行时工厂）**：按 `ai_agent` 任意一行现场组装，不复用
  启动期一次性装配的 `CustomerServiceAgentFactory`（构建时机与数据来源本质不同，见实施计划"上下文"
  一节）。装配步骤：① 校验智能体已启用（否则 `AGENT_DISABLED`）→ ② 查关联模型，解密 apiKey 后经
  `AdminModelFactory#buildModel` 现场构建 OpenAI 兼容 `Model`（新增方法，与已有的 `testConnectivity`
  区分"短生命周期探测请求" vs "可直接注入 Agent 的真实实例"）→ ③ 查 `ai_agent_mcp` 关联行，逐个用
  `McpClientBuilder` 动态注册进 `Toolkit`（参考 `McpToolkitConfigurer` 的写法，改为读数据库行；
  支持 `stdio`/`sse` 两种 `mcpType`，`config` JSON 解析出 command/args 或 url）→ ④ 查 `ai_agent_skill`
  关联行，把 `content`（SKILL.md 正文）落盘到 `./data/admin-skills/{agentCode}/{skillCode}/SKILL.md`
  后复用框架自带的 `FileSystemSkillRepository` 加载（不自造 Skill 解析逻辑）→ ⑤ `ReActAgent.Builder`
  组装；`capabilities` 含 `vibecoding` 时用 `HarnessAgent.Builder.fromAgent` 在内层 ReActAgent 上叠加
  本地沙箱（`LocalFilesystemSpec` + `IsolationScope.AGENT`），workspace 目录限定到
  `./data/admin-workspace/{agentCode}`。
- **API 版本坑**：项目锁定 agentscope 2.0.0-RC4，`stream(List<Msg>, StreamOptions, RuntimeContext)`
  这个重载只直接声明在 `ReActAgent`/`HarnessAgent` 各自的类上，未收敛进共享的 `Agent`/`StreamableAgent`
  接口（后续版本可能已收敛，本地开发分支的源码与 RC4 实际发布的 jar 不一致，靠 `javap` 核实实际 jar
  才发现）——`ChatService` 因此按运行时具体类型（`instanceof ReActAgent` / `instanceof HarnessAgent`）
  分派，而不能直接对 `Agent` 接口类型变量调用。
- **`AgentInstanceCache`**：`ConcurrentHashMap<agentCode, Agent>`，`computeIfAbsent` 保证同 agentCode
  并发只构建一次，惰性重建、不预热。智能体自身的 create/update/delete/enable/disable，以及它引用的
  模型/MCP/Skill 的 `update()`，都会 evict 受影响的 agentCode（model 变更需反查引用它的所有 agent 批量
  evict；MCP/Skill 同理反查 `ai_agent_mcp`/`ai_agent_skill`）——delete 路径不需要额外处理，批次三已加的
  引用校验本就阻止删除仍被引用的资源。
- **`AgentStateStore`/`PermissionContextState`**：`AdminAgentRuntimeConfig` 仿 `customer-web` 的
  `CustomerWebAgentConfig` 手法手动暴露 Bean（本模块已用 `spring.autoconfigure.exclude` 关闭 starter
  自动装配）。刻意用 `InMemoryAgentStateStore` + `PermissionMode.DEFAULT`（trivial，不拦截任何工具
  调用）——admin 工作区是运营调试场景，不需要 `customer-work.session.*` 那套四后端可切换的持久化能力，
  调用方也已经过 Sa-Token 鉴权，不需要在 Agent 内部再叠一层工具级授权。
- **Chat 流式对话（SSE）**：`ChatController` 返回 `Flux<ServerSentEvent<String>>`——本模块是 Spring MVC
  非 WebFlux，但 `reactor-core` 经 starter 传递可用，Spring MVC 6.x 原生支持控制器方法返回该类型做流式
  响应（内置 `ReactiveTypeHandler`），无需手动桥接 `SseEmitter`，与 `CustomerServiceController#chatStream`
  同一套写法。权限点复用 `workspace`（菜单聚合已在用的同一个 permCode），不新增按智能体粒度的权限点。
- **VibeCoding（降级版）**：明确不自研代码生成引擎——`VibeCodingService` 校验智能体确有 `vibecoding`
  能力后直接复用 `ChatService` 的流式对话；产物清单用"对话前后对比 workspace 目录文件快照"的降级方案
  （进程内 `Map<agentCode:sessionId, 快照>`，`stream()` 调用前拍照，`listChangedArtifacts()` 与当前状态
  比对 size+mtime），不做实时 `file_change` 事件（工程量大，一期明确砍掉，见实施计划 3.4 节）。
- 测试：`AgentInstanceCacheTest`（4，惰性重建/命中不重建/evict 后重建/evictAll）、
  `VibeCodingServiceTest`（5，能力校验两种拒绝场景/流式对话委托/快照 diff 正确识别新增与修改文件且
  不误报未改动文件）、`AdminModelFactoryTest` 新增 `buildModel` 两个用例（openai 成功构建/非 openai
  拒绝）。`AdminAgentInstanceFactory.build()` 的端到端装配（真实构建 Agent、注册 MCP 客户端）未覆盖
  单测——需要真实模型 API Key 或本地 MCP 进程，如实标注留给联调阶段，不假装已覆盖。全仓测试 405，
  BUILD SUCCESS。
- 文档：README §6.21 更新为"批次一~四"进度，新增"智能体运行时架构要点"小节。

### 入站防注入围栏 + 用户反馈闭环（P8）

安全防护与数据飞轮的第二条输入通道。

- **`PromptInjectionGuardMiddleware`（入站防注入围栏）**：落在 `onAgent`——`MiddlewareBase` 唯一"拦截
  整次 Agent 调用"的钩子。命中中英文常见注入/越狱模式（"忽略之前的指令"/"ignore the above
  instructions"/套取系统提示词/角色扮演绕过限制/`DAN` 等）即**不调用 `next`**，直接返回统一拒绝话术，
  不产生模型调用——省成本 + 防止注入内容触达推理。与 `ToolGuardMiddleware`（工具入参层，命中后改写为
  安全占位继续放行）刻意不同：一句已被识别为注入攻击的用户输入没有"安全改写后继续对话"的合理中间态，
  故为硬拦截而非改写。默认关闭；fail-open（围栏自身异常不打断正常对话）；指标
  `customerwork.prompt.guard.blocked`；config-driven 正则列表（`hooks.prompt-guard.injection-patterns`，
  与 `ToolGuard.destructivePatterns` 同一预编译 + 不区分大小写模式）。新增
  `PromptInjectionGuardMiddlewareTest`（5，覆盖 10 种默认模式的正/反例）。
- **消息级用户反馈闭环（点赞/点踩）**：`ChatResponse` 新增 `messageId` 字段（`/chat` 单一调用点新增，
  低风险），`POST /api/customer/feedback` 提交反馈、同 `messageId` 重复提交按最新覆盖（用户改主意允许
  更正）。`DOWN` 类型与 `QualityFeedbackRecorder`（系统主动质检）同一模式沉淀到 `FactLog`——是数据飞轮
  除系统主动质检外的**另一条用户主动输入通道**。`MessageFeedback`/`FeedbackStore`/`InMemoryFeedbackStore`/
  `JdbcFeedbackStore`（`cw_message_feedback` 表）/`FeedbackConfig`/`FeedbackService` 是第 5 次套用本仓库
  已验证的 Store SPI 模式。`FeedbackService` 复用 `TenantResolver`（第 3 处调用点）。反馈本身建模为不可变
  事实快照（record），非状态机实体——没有合理的"待确认反馈"这种中间态。**诚实边界**：只做"记录"，筛选
  回流是离线人工职责（同 `QualityFeedbackRecorder`）；V1 仅覆盖非流式 `/chat`，`/chat/stream` 逐 token
  输出没有单一终态对象可挂 `messageId`，需注入协议层事件才能覆盖，属后续扩展点。
  新增 `FeedbackStoreTest`（5）、`FeedbackServiceTest`（5）、`FeedbackConfigTest`（2）、
  `JdbcFeedbackStoreTest`（3，MySQL 门控）。
- 文档：README 特性表/API 表/闭环说明新增两条目；`docs/生产接口使用手册.md` 新增 §3.8"消息级反馈"、
  §9 补入站防注入围栏行为说明、接口总表增至 27 个端点；`docs/部署手册.md` 表数从 6 张更新为 7 张、
  JDBC store 从 4 个更新为 5 个；`mysql/schema.sql` 补 `cw_message_feedback` DDL；prod yml 新增
  `feedback.store-mode: jdbc`。

### SLA 升级引擎 + 业务数据分析聚合（P7）

在工单系统落地的基础上补齐运营视角：工单卡了多久要告警、这段时间业务运转得怎么样。

- **`HandoffSlaScheduler`（SLA 升级引擎）**：周期扫描 `PENDING`（超 `human-handoff.sla-pending-seconds`
  无人接单）与 `CLAIMED`（超 `human-handoff.sla-claimed-seconds` 未结案）两阶段超标工单，结构化告警
  日志 + 指标 `customerwork.handoff.sla.breach`（tag `stage=pending|claimed`）。与
  `ApprovalTimeoutScheduler` 的 "escalate" 分支同一设计语言——只读扫描 + 告警，不引入新状态、不做
  自动流转（人机切换没有"自动接单/自动结案"这种合理兜底）；每周期对仍超标工单重复告警，依赖下游
  日志/指标系统去重聚合（与既有 `ApprovalTimeoutScheduler` escalate 分支一致，不新增去重状态）。
  两阈值默认 0（禁用）。新增 `HandoffSlaSchedulerTest`（4）。
- **`FactLog.readRecords`（纯增量扩展）**：新增 `FactRecord(ts, tenant, fact)` + 按租户读取带时间戳的
  事实记录，供按窗口聚合统计；不改动既有 `read()` 方法与其调用方，零回归风险。
- **`BusinessAnalyticsService` + `GET /api/customer/analytics/business`**：一次性聚合审批 / 人机切换 /
  质检三个业务维度的窗口内统计（`ApprovalStats`/`HandoffStats`/`QualityStats`，均为纯数据 record，
  区别于有状态机行为的 `ApprovalRequest`/`HandoffTicket` 充血模型）——审批放行率与平均决策时长、
  人机切换平均接单/结案时长、质检失败数与均分，均附带**不受时间窗影响的当前积压快照**
  （`currentPendingBacklog` 等）。审批/人机切换维度在应用层内存过滤（两表体量小，属可控案例数
  而非海量流水），未新增按时间范围查询的 SQL 方法。质检维度按租户查询 `FactLog`（无法跨租户汇总）
  且用 try/catch 安全跳过非质检失败的事实（`FactLog` 还被 `InMemoryLongTermMemory` 写入纯文本长期
  记忆事实，双写入源已验证）。**诚实边界**：不提供"转人工率"——没有可靠的"时间窗内会话总量"数据源
  （已结束会话已从状态存储删除，无法反推历史开启量），不编造不可靠的分母。
  新增 `BusinessAnalyticsServiceTest`（5：放行率/平均决策时长/积压快照、接单结案时长、质检均分与
  非质检事实安全跳过）。
- 文档：README 特性表/API 表/闭环说明新增两条目；`docs/生产接口使用手册.md` 新增 §8.6"业务数据分析
  报表"、接口总表增至 24 个端点。

### 工单系统 + 人机切换闭环（P6）

补齐"AI→人工转接"此前只打日志、生成不落库随机字符串的空实现——转人工升级为可查询、可流转的工单闭环。

- **`HandoffTicket`（充血领域模型）**：状态机 `PENDING`（待坐席接单）→（claim）`CLAIMED`（处理中）→
  （resolve）`RESOLVED`（结案，会话可回收给 AI 续接），`claim`/`resolve` 各自前置状态校验、重复
  流转 fast-fail（`IllegalStateException`）；包级 `reconstruct()` 静态工厂供存储层重建跳过状态机校验。
- **`HandoffStore` SPI + `InMemoryHandoffStore` + `JdbcHandoffStore`**：与 `ApprovalStore` 同一模式——
  默认进程内实现离线可测，`human-handoff.store-mode=jdbc` 落 `cw_handoff_ticket` 表（`HandoffConfig`
  按 `@ConditionalOnMissingBean` 装配，复用 `session.mysql.*` 连接配置构建独立连接池）；下游声明同类型
  Bean 即可整体覆盖（如 Redis 实现）。JDBC 实现 `catch(Exception)` 而非 `catch(SQLException)`（同
  既有 JDBC store 约定：HikariPool 连接失败抛非受检异常）。
- **`HandoffService`**：create（AI 转出登记 PENDING）/ claim（坐席接单）/ resolve（结案回收）/
  list / listByStatus / find，单一防御点 `require()` 保证工单必须存在。
- **`HumanHandoffTools.transferToHuman` 重写**：注入 `HandoffService` 后真实登记工单（未注入退化为
  纯文案兜底，保持工具向后兼容）。**已知限制（如实记录，非本次范围）**：框架工具调用未打通
  RuntimeContext 注入，本工具与 `AfterSalesTools#submitRefund` 同样拿不到真实 sessionId，沿用同一
  占位值 `"agent-tool"`——工单暂无法精确关联发起会话。
- **`HandoffController`**：`GET /api/customer/handoffs`（按 status 过滤）、`GET /{id}`、
  `POST /{id}/claim`、`POST /{id}/resolve`；领域异常转 404/409；决策写入 `cw_audit_log` 审计留痕。
  与资金类 `ApprovalController` 不同，接单/结案不产生资金动作，未接入同等强度的操作员令牌鉴权。
- **`TenantResolver` 式的既有约定复用**：`ToolRegistrar` 新增 `HandoffService` 构造参数，3 处调用点
  （2 个测试 + `customer-web` `CustomerWebAgentConfig`）同步更新。
- **文档**：README 特性表/API 表/闭环说明新增人机切换条目；`docs/生产接口使用手册.md` 新增 §6
  "人机切换接口"（后续章节整体重新编号 §6→§10）；`docs/部署手册.md` 表数从 5 张更新为 6 张、
  JDBC store 从 3 个更新为 4 个；`mysql/schema.sql` 补 `cw_handoff_ticket` DDL；prod yml 新增
  `human-handoff.store-mode: jdbc`。
- 新增 `HandoffStoreTest`（13：状态机 + fast-fail + 委托存储）、`HandoffConfigTest`（2）、
  `JdbcHandoffStoreTest`（3，MySQL 门控）、`HumanHandoffToolsTest` 扩展（2：兜底 + 真实登记）。
  本轮全仓测试 starter 312 → 331（+19），全仓 BUILD SUCCESS，0 failures。

### 生产级线上故障定位（P5 — 可观测性纵深）

补齐"从用户报障到定位根因"链路上此前半实现/未实现的环节。

#### P0 — 全链路日志关联（requestId / sessionId 打通）
- **MDC 日志贯穿（Reactor Context → SLF4J MDC 桥接）**：此前 `RequestIdWebFilter` 已把 requestId 放进
  Reactor Context、logback pattern 也已写 `%X{requestId}`，但 WebFlux 反应式栈跨线程流转，ThreadLocal 的
  MDC 拿不到 Context 里的值——`%X{requestId}` 永远是空，日志无法按请求聚合（"断头路"）。新增
  `MdcContextLifter`（`CoreSubscriber` 实现，每个信号回调前把 Context 的 requestId/sessionId 同步到 MDC，
  Context 无该键时从 MDC 清除防线程复用串号）+ `MdcConfig`（`Hooks.onEachOperator` 全局织入，
  `observability.mdc-enabled` 默认开）。sessionId 由 `CustomerServiceController` 的 chat/stream/intent/agui
  经 `contextWrite` 注入。logback pattern 增加 `%X{sessionId}`。`RequestIdWebFilter.CONTEXT_KEY` 复用
  `MdcContextLifter.REQUEST_ID_KEY` 常量避免两处漂移。新增 `MdcContextLifterTest`（5 例）。
- **错误响应体带 requestId**：`GlobalExceptionHandler` 各处理方法接收 `ServerWebExchange`，从
  `X-Request-Id` 响应头取值放入错误体 `requestId` 字段（无则省略），用户报障可直接自助提供定位凭据。
  未处理异常日志改为占位符错误码 `UNHANDLED_ERROR`。新增 `GlobalExceptionHandlerTest`（3 例）。
- 修正 `docs/生产接口使用手册.md` §1.3/§9：明确 MDC 自动关联与错误体 requestId 的落地事实。

#### P0/P1 — 会话诊断聚合 API + 审计/FactLog 查询打通
- **会话故障诊断全景（`DiagnosticService` + `GET /api/customer/diagnostics/session/{sessionId}`）**：
  把此前散落在 StateStore / 对话阶段 / 槽位 / 审批 / 审计 / 质检六处、需人肉查 4 张表 + grep 日志才能
  拼出的会话现场，聚合为单个只读 `SessionDiagnostic`。**每个数据源独立 try/catch**——单源不可用
  （MySQL 瞬断 / 审计后端未接入）只标注到 `degradedSources` 并降级，绝不让诊断工具自身崩溃
  （它恰恰要在系统部分故障时还能用）。读取涉及 JDBC/文件 IO，统一在 `boundedElastic` 执行不阻塞事件循环。
- **审计查询 SPI（`AuditQuery` + `AuditRecord`）**：与写入侧 `AuditSink` 做接口隔离，仅可检索的
  `JdbcAuditSink` 实现（按 `agent_name=CustomerServiceAgent-<sessionId>` 后缀 LIKE 匹配、ESCAPE 转义
  通配符、时间倒序）；默认 `LoggingAuditSink` 不实现，诊断链路以 `ObjectProvider<AuditQuery>` 可选注入、
  缺失时优雅降级。审计事件下钻端点 `.../audit?limit=`。
- **FactLog 按会话过滤**：诊断聚合读取租户事实流水并按 sessionId 过滤，取最近 N 条。
- **`SlotFillingService.peek`**：新增只读窥视收集进度（不推进/不创建状态），供诊断读取。
- **`TenantResolver` 抽取（DRY 收敛）**：把此前在 `CustomerServiceAgentFactory`、`QualityFeedbackRecorder`
  各复制一份的租户解析逻辑收敛为唯一 `@Component`，二者与诊断链路统一调用（遵循"防御式编程只保留一处"约定）。
- 新增 `DiagnosticServiceTest`（3：多源聚合 / 审计后端可选 / 单源失败降级不崩溃）+
  `JdbcAuditSinkQueryTest`（2，MySQL 门控）；`docs/生产接口使用手册.md` 增 §7.5 诊断端点。

#### P1 — 慢请求/异常请求留证
- **慢请求留证（`LatencyMiddleware` 增强）**：端到端耗时超过 `hooks.latency.slow-request-threshold-ms`
  （默认 5000ms）的请求、或出错的请求，输出一条结构化慢请求日志（outcome/agent/耗时/错误）+ 计数指标
  `customerwork.agent.slow.requests`（按 outcome=SLOW/ERROR 打标签），借 MDC 自动带 requestId/sessionId，
  供事后按 requestId 复盘；工具调用序列已由审计日志承载，日志只留精简现场并指引用诊断 API 拉全景。
  用 `doOnEach` 在终止信号向下游传播前完成留证（而非 `doFinally` 事后异步执行），确保时序确定。
  新增 `LatencyMiddlewareSlowRequestTest`（4）。

#### P2 — 分布式追踪关联（W3C trace-context）
- **traceId ↔ 日志关联（`TraceContextWebFilter`，零外部依赖）**：解析上游传入的 W3C `traceparent` 头
  （`00-<32hex traceId>-<16hex spanId>-<flags>`），把 trace-id 放入 Reactor Context（键 traceId）经
  MDC 落到日志 + 回写 `X-Trace-Id` 响应头——本服务日志因此与上游 Jaeger/Tempo 链路共享同一 traceId，
  实现"外部分布式链路 ↔ 本服务日志"交叉关联。`observability.trace-correlation-enabled` 默认开。
  logback pattern 增 `%X{traceId:-}`。**诚实边界**：本项只做关联；进程内真正采集 span 并导出 OTLP/Jaeger
  需引入 OpenTelemetry SDK（当前构建未含、本地镜像不确定可用），属基础设施扩展点，可另行声明框架
  `Tracer` Bean 接入（见 `TracingConfig`）。新增 `TraceContextWebFilterTest`（4）。

#### P2 — 合成监控（主动探活）
- **合成监控（`SyntheticMonitor`）**：定时用固定探针会话打真实对话链路（`CustomerServiceService.chat`），
  校验能返回/非空/非兜底回复，结果发指标 `customerwork.synthetic.probe`（tag result=UP/DEGRADED/DOWN）
  + 结构化日志供告警，在用户上报前发现故障。探针用独立 sessionId、探测后清理状态。
  **默认关闭**（`synthetic-monitor.enabled=true` 才 `@ConditionalOnProperty` 装配）——每次探测真实调用模型
  产生费用。`CustomerServiceService.FALLBACK_REPLY` 提升为 public 供探针判定降级。prod yml 增 synthetic-monitor
  块（默认关、可环境变量开）。新增 `SyntheticMonitorTest`（4）。

### 生产加固与功能完善（P0-P3）

#### P0 — 生产关键项
- **审批工单持久化（ApprovalStore SPI）**：从 `PendingApprovalService` 抽取存储层为 `ApprovalStore` 接口 +
  `InMemoryApprovalStore` 默认实现（`@ConditionalOnMissingBean`），下游可声明 JDBC / Redis 实现覆盖默认，
  保证审批单重启不丢失——对涉及资金的退款审批至关重要。`approve`/`deny` 后调用 `store.update` 持久化状态变更。
  新增 `ApprovalConfig` 配置类、`ApprovalStoreTest`（10 例）。
- **会话级并发控制（sessionId 锁）**：`CustomerServiceService` 新增 `ConcurrentHashMap<String, ReentrantLock>` 
  会话级锁，同一 `sessionId` 的请求串行执行（`withSessionLock` / `withSessionLockFlux`），防止并发写 StateStore 
  导致状态覆盖。锁在 `boundedElastic` 上获取，不阻塞 Netty 事件循环线程。`endSession` 清理会话锁。
  新增 3 个并发控制单测（同会话串行 / 不同会话并行 / endSession 清理锁）。

#### P1 — 架构健壮性
- **SlotFillingService 持久化（SlotFillingStore SPI）**：从 `SlotFillingService` 抽取存储层为
  `SlotFillingStore` 接口 + `InMemorySlotFillingStore` 默认实现，`SlotFillingProgress` 从内部类提取为公共类。
  下游可声明 JDBC / Redis 实现保证退款表单多轮信息收集中途重启可恢复。新增 `SlotFillingStoreTest`（7 例）。
- **审批超时自动处理（ApprovalTimeoutScheduler）**：新增 `@Scheduled` 巡检器，周期性扫描 PENDING 审批单，
  超过 `human-approval.timeout-seconds` 未决策的按 `timeout-action` 处理——
  `escalate`（升级告警）或 `deny`（自动拒绝）。默认禁用（`timeout-seconds=0`）。
  新增 `ApprovalTimeoutSchedulerTest`（4 例）。
- **会话超时自动清理（SessionTimeoutScheduler）**：`CustomerServiceService` 新增会话活跃时间追踪
  （`sessionActivity` map），`SessionTimeoutScheduler` 周期性清理超过 `session.idle-timeout-minutes` 未活跃的
  会话（移除热 Agent 缓存 + 删除持久化状态 + 清理会话锁）。默认禁用（`idle-timeout-minutes=0`）。
  新增 `SessionTimeoutSchedulerTest`（3 例）。

#### P2 — 架构健壮性续
- **限流算法升级（滑动窗口）**：`RateLimitWebFilter` 新增滑动窗口算法支持（`security.rate-limit.algorithm=sliding-window`），
  用 `ArrayDeque` 记录每个请求的时间戳，过期出队，避免固定窗口边界突刺（2x 突刺问题）。
  原固定窗口算法保留为默认。新增 3 个滑动窗口单测。
- **模型成本熔断（ModelCostCircuitBreaker）**：新增 `@Component` 成本熔断器，按分钟 / 小时窗口追踪 token 消耗量，
  超限拒绝请求防刷量打爆成本。`tryConsume(int)` 原子检查 + 回滚，`isCircuitOpen()` 检查熔断状态。
  配置：`model.cost-control.enabled` / `max-tokens-per-minute` / `max-tokens-per-hour`。新增 9 个单测。
- **FactLog 文件轮转**：`FactLog` 新增文件大小检查与轮转——超过 `max-file-mb` 时归档为 `.1` / `.2`，
  超出 `max-archived-files` 的最旧文件自动删除。新增 3 个轮转单测。
- **Jacoco 覆盖率门槛**：starter POM 新增 `check-coverage` 执行（`verify` 阶段），
  行覆盖率 ≥ 50%、分支覆盖率 ≥ 40%，低于门槛构建失败。

#### P3 — 功能补全 + 工程质量
- **审计日志结构化存储（JdbcAuditSink）**：新增 JDBC 审计落地实现，把审计事件结构化写入 `cw_audit_log` 表
  （自动建表），支持按类型 / 时间 / Agent 维度查询。`mysql/schema.sql` 新增建表 DDL。
  下游声明 `DataSource` Bean + `JdbcAuditSink` Bean 即可覆盖默认 `LoggingAuditSink`。
- **LLM-as-Judge 回复质量评测（QualityEvalRunner）**：新增 `QualityEvalCase`/`QualityEvalReport`/`QualityEvalRunner`，
  用 LLM 对 Agent 回复进行质量打分（1-5 分），量化回复的相关性、准确性、完整性。
  与 `IntentEvalRunner`（离线确定性评测）互补。新增 5 个单测。
- **多 Agent 专家 Agent 缓存**：`MultiAgentOrchestrator.buildSpecialists()` 新增 double-check locking 缓存，
  首次构建后复用，避免每次 `consult` 重建 Agent（Agent 无状态可安全复用）。
  `clearSpecialistCache()` 支持热更新。
- **技能版本管理（SkillVersionManager）**：新增 `@Component` 技能版本管理器，从技能内容（Markdown）中
  解析版本号（`<!-- version: x.y.z -->` 或 `# version: x.y.z`），追踪当前加载版本，
  `checkUpdates()` 检测版本更新触发热重载。新增 7 个单测。

#### P4 — 生产就绪缺口收尾（安全 / 数据一致性 / 多实例 / 数据飞轮）
- **审批端点操作员身份鉴权**（`af10745`）：`ApprovalController.approve/deny` 的 `operator` 此前是客户端自报的
  query 参数（有默认值），任何持有通用 API Key 的调用方都能冒充任意坐席放行退款、且无审计留痕。新增
  `ApprovalAuthWebFilter`（只拦截 approve/deny 两个资金放行端点，按 `security.approval-auth.operators` 的
  token→操作员姓名映射解析身份）+ 决策成功后经 `AuditSink` 留痕。新增 7 个单测。
- **审批放行后下游执行失败补偿**（`3d6eb42`）：`PendingApprovalService.approve()` 此前裸调用
  `onApprove.get().accept(req)`（无 try/catch），下游回调（如实际打款）失败时异常直接抛出、无执行态记录，
  呈现"工单显示已放行、钱其实没动"且无法追踪/重试的静默不一致。新增 `ExecutionStatus`
  （`NOT_APPLICABLE`/`EXECUTED`/`EXECUTE_FAILED`）与 `ApprovalStatus` 分层，`retryExecutionFailures(maxAttempts)`
  巡检重试（`human-approval.max-execution-retry-attempts`，默认 3），`deny()` 回调同款异常隔离。新增 6 个单测。
- **补齐 `store-mode=jdbc` 死配置**（`7d9c2aa`）：`human-approval.store-mode` 配置项文档写着 memory\|jdbc 二选一，
  但 `ApprovalConfig` 无条件返回 `InMemoryApprovalStore`，jdbc 从未被实现；`SlotFillingStore` 则完全没有存储模式
  概念。新增 `JdbcApprovalStore`（写入 `cw_approval` 表）+ `slot-filling.store-mode` 配置 + `JdbcSlotFillingStore`
  （写入 `cw_slot_filling_progress` 表），均复用 `session.mysql.*` 连接配置。`ApprovalRequest` 新增包级
  `reconstruct()` 静态方法供存储层重建持久化状态。新增 10 个单测（含 assumeTrue(MySQL reachable) 门控的 JDBC
  往返测试）。
- **对话阶段状态机可插拔存储**（`59c89b4`）：`DialogStageService` 此前直接持有 `ConcurrentHashMap`（进程内），
  多实例部署下同一用户请求被负载均衡到不同实例会导致阶段状态"归零"回 `GREETING`。新增 `DialogStageStore` SPI
  + `InMemoryDialogStageStore` 默认 + `JdbcDialogStageStore`（写入 `cw_dialog_stage` 表）+ `dialog.store-mode`
  配置。新增 9 个单测（含"两个 store 实例共享同一 MySQL"的跨实例模拟测试）。
- **生产基线补新配置项 + 多实例部署注意事项**（`62982d2`）：`application-prod.yml` 补齐上述新能力对应配置
  （`security.approval-auth.enabled`、`human-approval/slot-filling/dialog.store-mode: jdbc`），
  `security.auth.enabled` 生产默认值翻转为 `true`（fail-safe），`management` 收敛暴露面为
  `health,prometheus` + `show-details: when-authorized`；新增 `customer-web/application-prod.yml`
  （此前完全没有，收敛 admin 控制台暴露面 + `write-token` 走环境变量）。新增"多实例部署注意事项"文档
  （`docs/生产就绪评估.md` + README）：如实说明 `security.rate-limit`/`model.cost-control`/
  `CustomerServiceService` 会话级锁仍是进程内实现，横向扩容会放大配额/锁失效；`MockComplaintBackend`
  需在生产替换为真实实现。新增 `ProdProfileConfigTest`（两个 web 模块各一份，用
  `YamlPropertySourceLoader` 离线校验 prod yml 语法与关键配置键，不激活 profile 真实启动）。
- **质检失败数据飞轮闭环**（`C2`）：新增 `QualityFeedbackRecorder`——质检不通过时把回复内容 + 扣分项
  作为事实写入 `FactLog`（按 sessionId 解析出的租户分文件追加），供离线复盘；`AgentAssistController` 的
  `/quality/inspect` 接入本类而非直接调用 `QualityInspectionService`。诚实边界：只做"记录"，"从事实流水
  筛选回流知识库/评测集"是离线人工或独立批处理任务的职责。新增 3 个单测。
- 本轮全仓测试 **202 → 309**（starter 290 + example 10 + downstream 1 + customer-web 8，0 failures，
  13 skipped 为 MySQL 门控集成测试）。
- **部署手册（docs/部署手册.md）**：操作向生产部署文档——部署架构与组件清单、基础设施准备、
  数据库初始化（5 张表 DDL 走 DBA 流程）、环境变量清单（必填/可选分级）、operators 秘密配置下发、
  Mock 实现替换核对、多实例注意事项、安全暴露面收口、可观测性告警、灰度上线流程、回滚预案、
  已知框架限制复查表、部署前最终核对单（13 项可勾选）。README §6.20 增加入口链接。
- **生产接口使用手册（docs/生产接口使用手册.md）**：面向接入方与坐席系统的对接文档——调用总则
  （X-API-Key 鉴权/429 限流/X-Request-Id 追踪/sessionId 租户二段式与并发约定）、17 个端点
  逐一给出 curl 请求响应示例、退款闭环双路径（对话内工具 / 多轮表单）与审批状态机字段语义
  （status + executionStatus 双字段判完成态）、SSE 客户端处理规则（message/done 事件、120s 空闲
  断流、代理缓冲）、对话内部逻辑接入方须知（快车道/阶段状态机/压缩/兜底）、错误码与故障排查表、
  新调用方上线自检清单（7 项）。所有接口签名/请求头/字段均从 Controller 与 DTO 代码逐一核对。

### Migration — AgentScope 2.0（`rc2.0` 分支）
- 全量迁移到 `io.agentscope:agentscope-harness:2.0.0-RC4`（经 `agentscope-bom` 管理），JDK 17。
- 会话持久化：`core.session.Session` → `core.state.AgentStateStore`（InMemory/JsonFile + extensions Redis/Mysql），
  Agent 无状态、按 `(userId, sessionId)` 由框架自动加载/持久化；删除手工 `saveTo/loadIfExists`。
- Agent 装配：`.memory()` → `.stateStore()`；调用入参全部带 `RuntimeContext`。
- 上下文压缩：`AutoContextMemory` → Harness `CompactionConfig`。
- 多 Agent：`core.pipeline.Pipelines` → Reactor 直接编排 / HarnessAgent Subagent。
- **多 Agent 真并行编排**：`MultiAgentOrchestrator` 的 `fanout` 修正为真并发——每个 `agent.call` 经
  `subscribeOn(Schedulers.boundedElastic())` 挪到独立线程（即便底层模型调用阻塞也能并行），叠加
  `flatMap` 并发限流（`multi-agent.max-concurrency`）、单专家 `timeout`（`multi-agent.timeout-seconds`）
  与 `onErrorResume` 错误隔离；新增确定性单测断言运行期峰值并发度（≥2 真并发 / =1 限流退化 / 错误隔离）。
- **多 Agent 智能路由 + reduce 归纳**：fanout 链路升级为 **路由 → 并行 → 归纳**——`routing-enabled` 用分诊器
  （`IntentResult`）只把问题发给相关专家（`expertsForIntent` 纯映射，other/失败广播全部）；`reduce-enabled` 用
  归纳器把多专家结论二次合成统一口径回复（单专家/关闭退化为拼接）。新增意图映射 / 退化路径单测。
- **并行编排可观测埋点**：`MultiAgentOrchestrator` 注入可选 `MeterRegistry`，暴露 `customerwork.mas.route{intent}`、
  `customerwork.mas.fanout.experts`、`customerwork.mas.expert{expert,outcome}`、`customerwork.mas.reduce{triggered}`
  到 `/actuator/prometheus`（无 Micrometer 时 no-op）；新增指标记录单测。
- **人工审批闭环（Human-in-the-Loop）**：新增 `approval` 包（`PendingApprovalService` + `ApprovalRequest` 充血状态机
  + `ApprovalType`/`ApprovalStatus`）与 `ApprovalController`（`GET /approvals`、`POST /approvals/{id}/approve|deny`）；
  退款工具 `submitRefund` 登记待审单（附审批单号），人工放行后经回调执行打款——**挂起 → 人工决策 → 生效** 闭环。
  与框架 Permission ASK（工具调用层闸门）互补；之所以落应用层而非绑定 `ReActAgent.CONFIRM_SINK_KEY`，因后者
  在 RC4 未暴露 Web 友好的公共回填 API。状态机终态不可变、重复决策 409、not-found 404。新增 7 个单测。
- **多 Agent 规则快车道（借鉴阿里商旅 AliGo 快慢车道）**：`MultiAgentOrchestrator` 路由前置规则层
  （`fast-route-enabled`）——关键词命中**唯一**意图直路由、跳过 LLM 分诊（省一次模型调用、提准降延迟）；
  命中多类/无命中再走 LLM 慢车道。`fastRouteIntent` 纯函数，新增 2 个确定性单测。
- **多轮槽位收集（借鉴 AliGo 事项收集智能体）**：新增 `slotfilling` 包（`Slot`/`SlotFillingForm`/
  `SlotFillingResult`/`SlotFillingService`）——按 (sessionId, form) 维护进度，带正则槽位任意句抽取、
  自由文本槽位追问轮取值；内置退款表单（订单号→原因）。`RefundFormController`（`POST /api/customer/forms/refund`）
  收齐后**串接 HITL** 生成待审退款单。新增 3 个确定性单测。
- **业务功能补全 · 主动服务（复用 Channel 推送）**：新增 `notification` 包（`NotificationChannel` 抽象 +
  `LoggingNotificationChannel` 默认 + `ProactiveNotificationService`）——订单状态通知 / 满意度回访；
  customer-web 提供 `FeishuNotificationChannel`（`@Primary`，复用飞书 webhook，未配置降级日志）覆盖默认。
  `ProactiveNotificationController`（`/api/customer/notify/order-status|survey`）。新增 3 个单测。
- **业务功能补全 · 坐席辅助 + 会话质检（借鉴 AliGo）**：`assist` 包（`AgentAssistService` 给坐席实时
  话术/知识/工具建议）+ `quality` 包（`QualityInspectionService` 规则质检：资金违规承诺判不通过、绝对化/禁语扣分）；
  `AgentAssistController`（`/api/customer/assist`、`/api/customer/quality/inspect`）。新增 6 个单测。
- **业务功能补全 · 售中**：`OrderBackend`（`default` 方法演进，不破坏 Acme/Custom 实现）+ `OrderTools` 扩
  `modifyAddress`(改址)/`cancelOrder`(取消)/`urgeShipment`(催发货)；`MockOrderBackend` 提供演示实现。
- **业务功能补全 · 会员/账户**：新增 `MemberBackend`/`MockMemberBackend`/`MemberTools` + `member` 工具组——
  `queryPoints`/`queryMemberLevel`/`resolveAccountIssue`。新增 `MemberToolsTest`。
- **业务功能补全 · 投诉工单**：新增 `ComplaintBackend`/`MockComplaintBackend`(内存工单)/`ComplaintTools`
  + `complaint` 工具组——`fileComplaint`(建单)/`queryComplaint`(查单)，投诉从"仅识别意图"升级为"可建单+可跟踪"。
  新增 `ComplaintToolsTest`。Tool Group 5→7，`ToolRegistrar` 构造增 Member/Complaint Backend（修齐 3 处调用点）。
- **业务功能补全 · 售前导购**：新增 `ProductBackend`/`MockProductBackend`/`ProductTools` + `presale` 工具组——
  商品咨询 `queryProduct`、推荐 `recommendProducts`、库存 `checkStock`、优惠 `queryPromotions`；
  `IntentResult` 意图加 `presale`。补全客服旅程"售前"半段（关联下单转化）。新增 `ProductToolsTest`（4 例）。
- **业务功能补全 · 售后**：`AfterSalesBackend`/`AfterSalesTools` 扩 5 个工具——退款进度 `queryRefundProgress`、
  退货 `submitReturn`、换货 `submitExchange`、价保 `checkPriceProtection`、发票 `requestInvoice`；
  `AfterSalesToolsTest` 补 5 例。`ToolRegistrar` 工具组 4→5、构造增 `ProductBackend`（修齐 3 处调用点）。
- **对话阶段状态机 / 动态 Prompt（借鉴 AliGo 状态机式 Prompt 组装）**：新增 `dialog` 包（`DialogStage` 枚举
  + `DialogStageService`）+ `DialogStageMiddleware`（第五段 `onSystemPrompt`）——按会话当前阶段
  （接待/收集/处理/确认/转人工）动态注入"聚焦当前主链路"指令，替代全量规则塞一个静态大 Prompt（降 token、提准）。
  中间件第 10 个，`@Component` 自动收集进 Agent。新增 4 个单测；starter 全套 164 tests 全绿。
- **自动化意图评测框架（借鉴 AliGo 测评系统）**：新增 `eval` 包（`EvalCase`/`EvalReport`/`IntentEvalRunner`）
  + classpath 评测集 `eval/intent-eval-cases.json`（用例沉淀复用）——量化规则快车道的准确率/覆盖率，
  离线确定性、可 CI 跑、可版本对比；`EvalReport.format()` 输出文本报告。新增 2 个单测（数据集加载 + 质量基线）。
  真实回复相关性评测需 LLM-as-judge（真实 Key），不在离线范围。
- **customer-web 端到端测试补强**：新增 `CustomerWebEndpointAvailabilityTest`（`@SpringBootTest` RANDOM_PORT +
  `TestRestTemplate`），离线确定性验证五套前端端点真实注册可达——actuator/health、OpenAPI、chat-completions、
  AG-UI、飞书 inbound 回调 + OpenAPI 装配。诚实标注覆盖边界：真实对话/事件序列/签名收发依赖真实 Key/公网/签名，
  企业微信 inbound 端点映射依赖 channel 启用（由 bean 装配测试覆盖）。customer-web 测试 1 → 7 个。
- **生产加固（对照框架 open issues 的缓解措施）**：四项面向生产的加固——
  ①`HarnessAgentFactory` 在 `HarnessAgent.Builder` 上显式补默认 `generateOptions`，规避 **#1644**
  （未设 `generateOptions` 时 `streamEvents()` NPE）；
  ②`ToolGuardMiddleware.onActing` 增加**破坏性命令拦截**——对工具字符串入参匹配危险正则
  （默认覆盖 `rm -rf`、删除 `.agentscope/workspace`、Windows `del /f|/s`、`format `）命中即改写为安全占位
  `[BLOCKED_BY_TOOL_GUARD]` 并 `TOOL-GUARD-DESTRUCTIVE` 告警计数，缓解 **#1898/#1896**（沙箱可删 workspace / 跨用户写），
  正则经 `hooks.tool-guard.destructive-patterns` 可整体覆盖；
  ③`CustomerServiceService` 意图分类失败与 chat 兜底新增 Micrometer 计数 `customerwork.intent.classify.errors`、
  `customerwork.chat.fallback`（`ObjectProvider<MeterRegistry>` 可选注入、无则 no-op），缓解 **#1852/#1699**
  结构化输出静默失效；
  ④`chatStream()` 增加 **SSE 空闲超时**（`stream.idle-timeout-seconds`，默认 120，`<=0` 禁用）——空闲超时以
  `STREAM_IDLE_TIMEOUT` 友好收尾而非挂死，缓解 **#1741**（SSE 关不掉导致连接泄漏）。新增 9 个单测。
- **生产就绪评估文档 + 生产配置基线**：新增 [docs/生产就绪评估.md](docs/生产就绪评估.md)——对 agentscope-java（RC4）
  120 个 open issues 与本项目实际链路的交叉评估结论（已实测排除的风险 / 已加固缓解 / 架构规避 / 部署侧规避 /
  仍受框架限制需等待修复的项 / 版本升级策略）；新增
  [application-prod.yml](customer-work-example/src/main/resources/application-prod.yml) 生产配置基线
  （skill 仓库切 filesystem 规避 #1979/#1985、session 切 redis/mysql 规避 #1769、harness.subagent 生产关闭
  规避 #1954 系列、context 压缩阈值保守规避 #1740、stream 空闲超时规避 #1741、model.fallback 启用自研
  `FallbackChatModel` 规避 #1850），逐项配置均在注释中标注对应 issue 编号；README 6.13b 标注框架内置
  `fallbackModel` 已知缺陷（#1850），customer-web 操作文档 Channel 部分新增"生产边界"声明（#1966/#1619，
  多用户共享群存在上下文串扰风险，生产建议单租户群部署）。
- **新增 2.0 能力**：Permission System（`PermissionConfig` 注入主 Agent）、Plan Mode、Compaction、
  Workspace/Sandbox、Subagent（统一经 `HarnessAgent.Builder.fromAgent(...)` 装配，见 `HarnessAgentFactory`）。
- **Middleware 五段**：8 个业务 Hook 全部迁移到 `MiddlewareBase`（ToolGuard/DynamicOptions/Masking/
  Observability/Audit/Latency/SelfCorrection/HumanApproval + 新增 TenantContext），覆盖
  `onAgent/onReasoning/onActing/onModelCall/onSystemPrompt`；`ObjectProvider<Hook>`→`ObjectProvider<MiddlewareBase>`。
- **Harness 深度**：分层记忆（`MemoryConfig` MEMORY.md + `environmentMemory`）、超大工具结果落盘
  （`ToolResultEviction`）、技能自进化（`SkillCurator`+管理工具）、Plan 文件目录、`additionalContextFile`、
  org 维度（`RuntimeContext.put`）。
- **安全沙箱执行**：`harness.sandbox.mode` = local（子进程）/ docker（容器，均 Harness 内置）；
  远端 k8s/e2b/daytona/agentrun 需 `agentscope-extensions-sandbox-*`。
- **配套前端 `customer-web` 模块**（Spring MVC，独立于 WebFlux 对话 API；同一个 `customerServiceAgent` Bean
  被四套官方能力接管）：
  - **admin**（`agentscope-admin-spring-boot-starter`）：管理控制台——会话/工具/权限/用量/子智能体端点 + Swagger UI，
    集成 AgentEvent 与 Permission HITL。
  - **chat-completions-web**（`agentscope-chat-completions-web-starter`）：OpenAI 兼容 `POST /v1/chat/completions`
    （同步 + SSE 流式）+ 内置聊天页 `/`。真机自测：自我介绍 / 订单查询(触发工具) / 流式 三轮真实对话通过。
  - **AG-UI**（`agentscope-agui-spring-boot-starter`）：`POST /agui/run` 标准富事件协议（SSE 类型化事件）。
    真机自测：事件流 `RUN_STARTED→TEXT_MESSAGE_*→RUN_FINISHED` + 真实回复通过。
  - **Studio**（`agentscope-extensions-studio`）：轨迹推送到外部 Studio 应用做可视化（默认关，连接失败优雅降级）。
  - **Channel·钉钉**（`agentscope-extensions-channel-dingtalk`）：`HarnessAgent.fromAgent(...).channel(DingTalkChannel)`
    Stream 模式接入钉钉机器人，群内 @ 即可对话（默认关，需 appKey/appSecret/robotCode；连接失败优雅降级）。
  - **Channel·飞书**（`agentscope-extensions-channel-feishu`）：inbound 应用+事件回调
    `/api/channels/feishu/{channelId}/callback`（url_verification 握手已实测：正确 token→200、错误→401）；
    outbound `FeishuWebhookNotifier` + `POST /push/feishu` 经自定义机器人 webhook 推送（关键词可配）。
  - **Channel·企业微信**（`agentscope-extensions-channel-wecom`）：inbound 应用+回调
    `/api/channels/wecom/{channelId}/callback`（GET URL 验证，实测端点映射+签名校验 401）。
  - 飞书/企业微信完整消息流转需自建应用回调地址指向公网可达地址；详见 [docs/customer-web操作文档.md](docs/customer-web操作文档.md)。
- **不可迁移**（框架移除）：TTS（`TTSHook`/`DashScopeRealtimeTTSModel`）、`PlanNotebook`、`Pipelines`、
  `SessionManager`/`StateModule` —— 详见 [docs/MIGRATION-2.0.md](docs/MIGRATION-2.0.md)。

### Changed
- 工程拆分为多模块：`customer-work-spring-boot-starter`（可复用，含 `@AutoConfiguration` 自动装配）
  + `customer-work-example`（可运行示例，包 `com.richard.fyoung.customerworkapp`）
  + `customer-work-downstream-app`（下游接入示例，包 `com.acme.support`，含接入契约测试）
- starter 改用 HikariCP（替代 spring-jdbc），不再触发 DataSourceAutoConfiguration，下游零排除即插即用

### Added
- 多 Agent 编排（Pipeline fanout/sequential）、AG-UI 协议、TTS Hook
- 记忆/RAG 多后端：内存 / 百炼 / Mem0 / ReMe / 真实向量(SimpleKnowledge) / Dify
- Skill：classpath/filesystem 仓库、运行时加载、代码执行
- 模型层：多厂商（dashscope/openai/anthropic/gemini/ollama）+ 私有化兜底 FallbackChatModel
- 可观测：Micrometer 指标 + 原生 Tracing + Actuator + 优雅停机 + 定时维护
- 接入层安全：API Key 鉴权 + 限流；Nacos 配置中心（提示词热更新）
- Swagger / OpenAPI（springdoc-webflux）
- 业务工具后端接口化（`tool.backend.*`），使用者实现接口即可接自有系统
- 开源治理：LICENSE(Apache-2.0)、CONTRIBUTING、CODE_OF_CONDUCT、Issue/PR 模板、Dependabot、Docker Compose

### Security
- 移除仓库内硬编码密钥，全部改为环境变量注入

## [1.0.0]
- 基于 AgentScope Java 1.0.12 的生产级智能客服核心链路（会话/意图/工具/流式/持久化）
