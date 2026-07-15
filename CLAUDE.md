# customer-work 项目工作手册（AI 会话用）

基于 AgentScope Java 2.0.0 GA 的生产级智能客服系统 + 后台管理系统。本文件只放**每次会话都需要的事实与坑**；
细节现场读代码和 docs/ 下的文档，不要在这里复制它们。

## 模块结构

| 模块 | 说明 |
|---|---|
| `customer-work-spring-boot-starter` | 可复用智能体基础设施（模型/记忆/RAG/工具SPI/中间件/调度等），`@AutoConfiguration` 自动装配 |
| `customer-work-app` | 可运行客服示例（端口 8080） |
| `customer-channel` | 多渠道接入演示模块（官方五套前端能力接入：admin/chat-completions/AG-UI/Studio/Channel，端口 8081），非主链路必需 |
| `customer-admin-server` | 后台管理系统后端（Spring MVC + MyBatis-Plus + Sa-Token，端口 8082，独立库 `customer_admin`） |
| `customer-admin-web` | 后台管理前端（Vue3+TS+Vite+Element Plus，**非 Maven 模块**，端口 5174） |
| `customer-user-mobile` | 智能客服用户端 H5（Vue3+TS+Vite+Vant4，**非 Maven 模块**，端口 5175，proxy → 8080） |

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
- 测试基线：全仓 **873 个**（starter 497 + app 56 + customer-channel 8 + admin-server 312）。
  外部依赖门控测试：MySQL(root/root)、Redis(密码 123456)、Nacos(nacos/nacos:8848)，不可达自动跳过。
- 模块 A 改完给模块 B 用时，先 `mvn install -Dmaven.test.skip=true -Djacoco.skip=true`（B 解析的是本地仓库的 jar，
  不是 A 的工作树）；根 pom 变更后父 POM 也要 `mvn -N install`，否则 B 读到旧版本号。

## 文档地图（哪个问题去读哪个文档）

- 功能总表/配置项/接口速查 → `README.md`
- 1.x→2.0 API 映射、RC4→GA 变更、issue 重新核对 → `docs/MIGRATION-2.0.md`
- 框架 open issues 与本项目链路的交叉评估 → `docs/生产就绪评估.md`
- 生产部署步骤/环境变量/灰度回滚 → `docs/部署手册.md`
- 架构原理/时序图/UML → `docs/详细技术文档.md`
- 用户工单系统（7 态状态机/WS 帧协议/用户端/坐席端）→ `docs/详细技术文档.md` 工单章节 + `docs/生产接口使用手册.md`
- 后台管理系统需求 → `docs/AI编码助手需求文档.md`（admin-server 相关）

## 项目编码规范（全局规范之外的项目特有约定）

- 日志只用 info/error（不用 warn），日志文本英文，error 带错误码占位符：
  `log.error("xxx failed, code={}, id={}", "MODULE-ACTION-FAIL", id, e)`
- 持久化扩展走 Store SPI 模式（接口 + InMemory 默认 + MyBatis-Plus 实现 + `@ConditionalOnMissingBean`，
  已套用 8 次：Approval/SlotFilling/DialogStage/Handoff/Feedback/Ticket/UserAccount/ChatLog），别发明新模式。
  持久层规范：贫血 DO(entity/)+BaseMapper(mapper/)+复杂 SQL 进 resources/customerwork/mapper/*.xml，
  代码里禁止手写 SQL；独立 customerWorkDataSource/SqlSessionFactory（CustomerWorkPersistenceConfig），
  不污染宿主 MyBatis 环境；建表种子统一走 SchemaInitializer（customer-work-schema.sql，与 mysql/schema.sql 同步）。
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
