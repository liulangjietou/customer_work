# customer-work 项目工作手册（AI 会话用）

基于 AgentScope Java 2.0.0 GA 的生产级智能客服系统 + 后台管理系统。本文件只放**每次会话都需要的事实与坑**；
细节现场读代码和 docs/ 下的文档，不要在这里复制它们。

## 模块结构

| 模块 | 说明 |
|---|---|
| `customer-work-starter` | 可复用智能体基础设施（模型/记忆/RAG/工具SPI/中间件/调度等），`@AutoConfiguration` 自动装配 |
| `customer-work-app-server` | 可运行客服示例（端口 8080） |
| `customer-channel` | 多渠道接入演示模块（官方五套前端能力接入：admin/chat-completions/AG-UI/Studio/Channel，端口 8081），非主链路必需 |
| `customer-admin-server` | 后台管理系统后端（Spring MVC + MyBatis-Plus + Sa-Token，端口 8082，独立库 `customer_admin`；**登录态存 Redis**，Redis 不可达则登录 fail-closed，见 `AdminSaTokenDaoConfig`） |
| `customer-admin-web` | 后台管理前端（Vue3+TS+Vite+Element Plus，**非 Maven 模块**，端口 5174） |
| `customer-work-app` | 智能客服用户端 H5（Vue3+TS+Vite+Vant4，**非 Maven 模块**，端口 5175，proxy → 8080） |

## 分支策略

- `main` = AgentScope 2.0.0 GA（2026-07 起）。**有分支保护，禁止直接 push，必须走 PR**。
- `legacy-main-1.0.12` 标签 = 升级前 1.0.12 最后状态；`rc2.0` 分支 = RC4 首轮迁移存档；`ga2.0` 分支 = 已并入 main 的历史开发分支（保留不删）。

## 构建与测试（关键坑，全部实测踩过）

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # shell 默认 java 是 1.8，必须显式切 17
mvn -gs scripts/settings-central-direct.xml -s scripts/settings-central-direct.xml clean test -Djacoco.skip=true
```

- **`-gs` 与 `-s` 必须同传**同一 settings 文件：Maven 会合并全局+用户两处 `activeProfiles`，只传 `-s` 时
  全局镜像 profile 仍生效，会挂死在内网 Nexus 上（无报错、0% CPU，极难判断）。
- **依赖版本变更后必须 `clean`**：增量编译不检测 classpath 变化，会误报编译成功。
- **跳过 jacoco 用 `-Djacoco.skip=true`**（不是 `jacoco.check.skip`，那个对本项目的绑定无效）。
- `customer-admin-server` 测试需要 `export ADMIN_MYSQL_PASSWORD=root`（yml 默认值与本机不符时）。
- 测试基线：starter **1177** + admin-server **741** + app 78 + customer-channel 65 + gateway 1
  （2026-08-10 B1+B2+B3+B4：B1 租户地基 starter +14 / admin +11，B2 水平扩展 starter +15，B3 配额计费 starter +12，B4 配置版本化 admin +9。
  MySQL 不可达时 admin 会少跑 1 个门控用例，显示 721 属正常。
  上一版基线是 2026-08-06 的 starter 1136 + admin 722；更早是 2026-08-05 的 starter 1054 + admin 711；
  admin-server 更早的实际基线已是 707，CLAUDE.md 曾记的
  701 系陈旧数字。starter 已按下方规则排除 2 个环境门控测试。此前 feature/sink-common-to-starter 把 admin
  十项通用能力下沉 starter：核心逻辑测试随迁，故 starter +226 / admin −106，admin 侧只保留薄壳职责测试；
  PR #68 修掉多构造器缺 @Autowired 后 `ApplicationContextTest` 已恢复，不再需要额外排除）。
  门控集成测试依赖：PaddleOCR serving(localhost:8868)、MinIO(localhost:9000)，不可达自动跳过。
  外部依赖门控测试：MySQL(root/root)、Redis(密码 123456)、Nacos(nacos/nacos:8848)，不可达自动跳过。
- **本机跑全量要排除 2 个环境门控测试**（当前 MinIO 的 9000 被 kb-rag 栈占、Redis 无密码，共 4 个用例必挂；
  starter 一挂会让下游模块整体 skip，`-fae` 也救不回来）：
  `-Dtest='!MinioAttachmentFileStorageIntegrationTest,!RedisSessionPersistenceTest' -DfailIfNoSpecifiedTests=false`
- 模块 A 改完给模块 B 用时，先 `mvn install -Dmaven.test.skip=true -Djacoco.skip=true`（B 解析的是本地仓库的 jar，
  不是 A 的工作树）；根 pom 变更后父 POM 也要 `mvn -N install`，否则 B 读到旧版本号。

## 文档地图（哪个问题去读哪个文档）

- 项目概览/模块说明/全景架构图 → `README.md`
- 多租户隔离模型/逐表归属/身份链路 → `docs/多租户架构设计.md`
- 功能总表/配置项/接口速查/各功能用法 → `docs/功能与配置全量参考.md`
- 1.x→2.0 API 映射、RC4→GA 变更、issue 重新核对 → `docs/MIGRATION-2.0.md`
- 框架 open issues 与本项目链路的交叉评估 → `docs/生产就绪评估.md`
- 生产部署步骤/环境变量/灰度回滚 → `docs/部署手册.md`
- 架构原理/时序图/UML → `docs/详细技术文档.md`
- 用户工单系统（7 态状态机/WS 帧协议/用户端/坐席端）→ `docs/详细技术文档.md` 工单章节 + `docs/生产接口使用手册.md`
- 后台管理系统需求 → `docs/AI编码助手需求文档.md`（admin-server 相关）

## 项目编码规范（全局规范之外的项目特有约定）

- **通用功能/基础组件优先下沉 `customer-work-starter`**：开发前先判断归属——只服务当前业务模块的留在
  业务模块；可复用的通用能力放 starter，走既有 SPI + `@ConditionalOnMissingBean` 自动装配模式。
  拿不准归属时先问，不要默认放业务模块。starter 改完给下游用要先 `mvn install`（下游解析本地仓库 jar）。
- 日志只用 info/error（不用 warn），日志文本英文，error 带错误码占位符：
  `log.error("xxx failed, code={}, id={}", "MODULE-ACTION-FAIL", id, e)`
- 持久化扩展走 Store SPI 模式（接口 + InMemory 默认 + MyBatis-Plus 实现 + `@ConditionalOnMissingBean`，
  已套用 8 次：Approval/SlotFilling/DialogStage/Handoff/Feedback/Ticket/UserAccount/ChatLog），别发明新模式。
  持久层规范：贫血 DO(entity/)+BaseMapper(mapper/)+复杂 SQL 进 resources/customerwork/mapper/*.xml，
  代码里禁止手写 SQL；独立 customerWorkDataSource/SqlSessionFactory（CustomerWorkPersistenceConfig），
  不污染宿主 MyBatis 环境；建表种子统一走 SchemaInitializer（customer-work-schema.sql，与 mysql/01-agent-scope-customer-work/ 同步）。
- **admin 库新增 Flyway 迁移必须同步一份到 `mysql/02-customer-admin/`**（文件名加数字前缀：`<版本号>-V<版本号>__xxx.sql`，
  字典序即执行序）。那个目录是 Flyway 迁移的镜像副本，供手工初始化与 **CI 建库**使用；漏同步不会影响本地
  （本地走 Flyway），但 CI 从空库灌脚本时会在依赖该表的后续脚本上炸掉——V27/V28/V36/V37/V38/V39/V41 就这么漏了 7 个，
  让 main 的 CI 从 PR #65 起连红 6 次（表现为 `Table 'customer_admin.cw_agent_call_log' doesn't exist`）。
- **迁移脚本里不要写库名前缀**（`INSERT INTO \`customer_admin\`.\`xxx\``）：脚本执行时已经 USE 到目标库，
  写死库名会让脚本换库不可用，验证/多环境时甚至串库写到别的库去。V14 踩过。
- **多租户（B1 起）**：隔离靠 MyBatis-Plus `TenantLineInnerInterceptor` 全局改写，租户值取自
  `TenantContext`（starter 的 ThreadLocal），默认关闭（`customer-work.tenant.enabled` / `admin.tenant.enabled`）。
  设计与逐表归属见 `docs/多租户架构设计.md`。三条硬约定：
  ① **新增业务表一律带 `tenant_id`**，不要往忽略清单里加——清单越短越安全，加表等于放弃该表的自动隔离；
  ② **有意的跨租户查询必须走 `CrossTenantOperations`**（可 grep 的白名单），不要靠"给上下文塞特殊值"；
  ③ **权限查询用用户归属租户，数据查询用当前视角租户**（`AdminStpInterfaceImpl` 已按此实现），
  混用会让运营方切视角后当场失去全部权限。缺上下文时持久层 fail-closed 抛错，这是刻意的。
- **水平扩展（B2 起）**：限流与成本熔断共用 `WindowCounter` SPI、会话串行锁走 `SessionLock` SPI，
  默认进程内，多副本部署切 `customer-work.distributed.{counter-mode,session-lock-mode}=redis`。
  两条约定：① Redis 实现失败一律**降级进程内**而非放行（保护性能力不能因基础设施故障消失）；
  ② 会话锁必须用 `RPermitExpirableSemaphore` 而非 `RLock`——加锁在 Reactor 链、释放在 `doFinally`，
  不保证同线程，RLock 会抛 `IllegalMonitorStateException`。K8s 清单见 `deploy/k8s/`。
- **配额与计费（B3 起）**：租户 token 配额走 `TenantQuotaGuard`（starter），判定在
  `CustomerServiceService` 入口（能打断），记账搭 `AgentCallTimingMiddleware` 里 token 的唯一落点。
  配额表 `cw_tenant_quota` 落**客服端库**（运行时要读），admin 经跨库门面维护——照内容风控三表先例。
  **实时只拦 token，金额走 T+1 账单**（`cw_tenant_usage_daily`，金额按归集时单价落库，调价不改历史账）。
- **配置版本化（B4 起）**：`CustomerWorkConfigPublisher` 发布时留一份完整快照到 `ai_config_version`，
  **回滚 = 把旧内容作为新版本再发一次**（不删后续版本，历史只增，任何时刻都能回答线上是哪一版）。
  灰度以租户为单元：写 `<主dataId>-tenant-<租户码>`，客服端配 `nacos.tenant-code` 后先读它、读不到回落主 dataId——
  客服端不理解"灰度"，只是多试一个更具体的 dataId。灰度撤销时 Nacos 回调空串，**必须主动回读主 dataId**，
  否则实例会一直停在灰度版本上。
- 业务工具后端走 `tool.backend.*` 接口 + `@ConditionalOnMissingBean` Mock，下游声明同类型 Bean 覆盖。
- 持久层异常兜底必须 `catch(Exception)`（HikariPool/MyBatis 初始化异常是 RuntimeException）。
- 给 `ToolRegistrar` 加构造参数前先 `grep -rn "new ToolRegistrar("`（多处调用点要同步）。
- admin-server 排除了 starter 的自动装配（`spring.autoconfigure.exclude`），需要 starter 能力时在自己的
  `@Configuration` 里显式 new，不要假设容器里有现成 Bean。

## 定时任务（XXL-JOB）约定

- starter：`customer-work.scheduler.xxl-job.*` 配置化，官方 `XxlJobAgentScheduler`（cron 在调度中心配，代码侧只注册 JobHandler）。
- admin-server：通用 JobHandler `agentScheduledTask`，调度中心任务参数填 `ai_scheduled_task.task_code`；
  手动触发接口不依赖 XXL-JOB。执行器 appname：`customer-work-executor`(9999) / `customer-admin-executor`(9998)。

## 本机环境

本机专属信息（容器、凭据、IDE 坑）见 `CLAUDE.local.md`（gitignored，不进仓库）。
