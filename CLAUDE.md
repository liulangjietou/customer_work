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
- `scripts/settings-central-direct.xml` 里写死了 `<localRepository>/Users/zhangfuqiang/mavenjar</localRepository>`，
  **换台机器会直接报 `Could not create local repository`**；此时改用仓库根的 `settings-central-direct.xml`
  （同名但不指定 localRepository，走默认 `~/.m2/repository`）。
- **依赖版本变更后必须 `clean`**：增量编译不检测 classpath 变化，会误报编译成功。
- **跳过 jacoco 用 `-Djacoco.skip=true`**（不是 `jacoco.check.skip`，那个对本项目的绑定无效）。
- `customer-admin-server` 测试需要 `export ADMIN_MYSQL_PASSWORD=root`（yml 默认值与本机不符时）。
- 测试基线：starter **1466** + admin-server **858** + app **95** + customer-channel 65 + gateway 1（合计 **2485**）
  （2026-08-20 公共常量收敛批次实测：全模块 BUILD SUCCESS，0 失败 0 错误，6 skip。
  本批次自身加 starter **+2**（`SharedConstantAlignmentTest`：已收敛值不得重新声明 + 同值不得多处定义）。
  **同值不同概念别硬合**：本批次差点把 `sys_operation_log.result` 与 `ai_coding_audit_log.result`
  合成一个常量（都是 `1=成功`），编译报错才发现是两张表——判定标准写在下方「项目编码规范」。
  上一版基线 2026-08-20 yml 瘦身 + 装配收敛批次：合计 2483。）
  （2026-08-20 yml 瘦身 + 装配收敛批次实测：全模块 BUILD SUCCESS，0 失败 0 错误，6 skip。
  本批次自身加 app-server **+2**（`YmlTrimEquivalenceTest`：yml 瘦身等价性 + 规模不回弹）。
  上一版基线 2026-08-20 M1-M4 系统性修复批次：合计 2481。）
  （2026-08-20 M1-M4 系统性修复批次实测：全模块 BUILD SUCCESS，0 失败 0 错误，
  排除 `RedisSessionPersistenceTest`；MySQL/Redis/MinIO 在线、Nacos 不可达故其门控测试跳过。
  本批次自身加 starter **+23**（装配对齐门禁 3 + 会话隔离 3 + 附件隔离 6 + 上下文预算 7 + 知识盲区双路径 4）。
  **本批次起：任何 `ReActAgent.builder()` 必须调 `governanceAssembler.applyTo(builder)`**，
  见下方「项目编码规范」第一条；`AgentAssemblyAlignmentTest` 会对此下断言。
  **两个不走 starter 自动装配的模块要特别注意**：customer-channel 用 `@SpringBootApplication`
  只扫自己的包（starter 的 `@Component` 拿不到，须显式 `@Bean`）；admin 有自己的
  `common.log.SensitiveDataMasker`，声明同名 Bean 会抛 `BeanDefinitionOverrideException`。
  这两个坑都是全量测试跑出来的，单模块测试照不出来。
  上一版基线 2026-08-19 Skill 技能包下载批次：starter 1441 + admin 858，合计 2458）
  （2026-08-19 Skill 技能包下载批次实测：starter 1441 / 5 skip、admin 858 / 1 skip，BUILD SUCCESS，
  排除 `RedisSessionPersistenceTest`。本批次自身加 admin +7。
  **导入/导出这类成对功能，测试要真的做一次往返**（导出的包再喂给解析器比对），
  只断言"zip 里有哪几个条目"照不出结构漂移。
  sys_permission 下次从 **248** 起、admin Flyway 下次 **V64**、customer-work Flyway 下次 **V9**。
  上一版基线 2026-08-19 语义缓存流式接入批次：starter 1441 + admin 851，合计 2451）
  （2026-08-19 语义缓存流式接入批次实测：starter 1441 / 5 skip、admin 851 / 1 skip，BUILD SUCCESS，
  排除 `RedisSessionPersistenceTest`。本批次自身加 starter +9。
  **Mapper 的 `resultType` 不要直接指向 record**：record 没有 setter，MyBatis 自动映射落不进去，
  得靠构造器映射（依赖编译期 `-parameters`）。项目一律"贫血 DO 接结果、Store 转领域对象"，
  聚合查询也照此办（`SemanticCacheScopeDO`）。
  **新加的 Mapper XML 若没有门控测试覆盖，手工在临时库跑一遍**——语义缓存没有 Mybatis 门控测试，
  `selectScopes` 的聚合与租户改写是手工验的。
  上一版基线 2026-08-19 CSAT 看板修复批次：starter 1432 + admin 851，合计 2442）
  （2026-08-19 CSAT 看板修复批次实测：starter 1432 / 5 skip、admin 851 / 1 skip，BUILD SUCCESS，
  排除 `RedisSessionPersistenceTest`。本批次自身加 starter +11。
  **加纯数据迁移前先想清楚它幂等不幂等**：幂等的（如 V6 归一）可以不给 `resolveBaselineVersion` 补判定，
  让完整镜像库重跑一次即可，代价只是多一行历史；不幂等的必须补判定，否则重跑撞唯一键直接失败。
  相应地，`CustomerWorkSchemaMigrationIntegrationTest` 里空库/legacy/镜像三处历史行数断言都要跟着加。
  **改已应用过的迁移文件（含只改注释）会让 Flyway checksum 校验失败拒绝启动**——要改列注释就在新迁移里
  `ALTER TABLE ... MODIFY COLUMN`，别回头动 V1。
  上一版基线 2026-08-19 后台用户配额批次：starter 1421 + admin 851，合计 2431）
  （2026-08-19 后台用户配额批次实测：starter 1421 / 5 skip、admin 851 / 1 skip，BUILD SUCCESS，
  排除 `RedisSessionPersistenceTest`。本批次自身加 starter +8、admin +11。
  **纯种子迁移（只 INSERT、不改结构）要在 `CustomerWorkSchemaMigrator#resolveBaselineVersion`
  里补一条数据判定**——那套"完整镜像接管"只认结构，判不出来就会重跑迁移撞唯一键。
  上一版基线 2026-08-18 数据权限批次：starter 1413 + admin 840，合计 2405）
  （2026-08-18 数据权限批次实测：starter 1413 / 5 skip、admin 834 / 1 skip、app 86，BUILD SUCCESS，
  排除 `RedisSessionPersistenceTest`。本批次自身加了 admin **+45**（数据范围枚举/上下文/白名单/SQL 改写/
  范围解析 30 + 角色范围校验 5 + 装配门控 7 + 会话归属放行边界 3），starter 只改忽略清单常量故条数不变；
  其余差额来自同日合入 main 的 PR #113/#114/#115。**跑 admin 全量前先确认本机 Flyway 版本号**——
  本机开发库常被并行分支占号，见下方"项目编码规范"里的 Flyway 版本号约定。）
  （2026-08-18 B7 主体配额批次实测：starter 1388 / 5 skip、admin 754 / 1 skip、app 86，BUILD SUCCESS，
  排除 `RedisSessionPersistenceTest`。**本批次自身只加了 starter +46**
  （主体配额 39 + 滑动求和 4 + 用户等级 3），其余差额来自 2026-08-14 之后合入 main 的批次；
  admin 侧只加薄壳与跨库门面故未加用例。
  **改客服端库 schema 时记得同步改 `CustomerWorkSchemaMigrationIntegrationTest` 的表数断言**——
  它硬编码了"空库迁移后应有 N 张表"，加表必挂，这是预期内的断言更新而不是 bug。
  上一版基线 2026-08-14：starter 1323 / admin 753 / app 80，合计 2222；
  排除了 `RedisSessionPersistenceTest`（本机 Redis 无密码）。上一版 B6 实测为 starter 1319 / admin 747，MinIO 当时未起；
  MinIO 起着时那 3 个门控用例会跑起来，总数不变、skip 相应减少）
  （2026-08-13 B6 运营闭环批次：评测链路打通 + badcase 回流 + 语义缓存 + 模型分级路由 +
  提示词版本归因 + CSAT + 知识盲区 + 死信队列，starter +118，admin 侧只加薄壳与跨库门面故不变。
  **新增可选依赖的 Bean 时必须配一个装配门控测试**：语义缓存一度因 starter 从未装配过
  `EmbeddingClient` 而整体静默失效（`ObjectProvider.getIfAvailable()` 恒为 null），
  而 Service 层单测全程注入 mock，21 条用例照样全绿——只测逻辑照不出"Bean 根本不存在"。
  **改本批次内尚未合并的 V52/V53 会让本机 Flyway 校验失败**（checksum 变了），
  清掉 `flyway_schema_history` 里对应版本记录与它插入的菜单行即可重跑，不是代码问题。
  上一版基线 2026-08-13 B5 存储落库批次：三层记忆 L2/L3 与 Harness 分层记忆默认落 MySQL、技能库支持
  `skill.repository=mysql`、文件一律走 MinIO。末尾"彻底去掉本地盘"那一步删实现连带删了它们的专属
  用例，故条数比中途峰值（starter 1217 / admin 754 / app 83）低，属预期。
  上一版基线 2026-08-11 PR #90 starter 治理：包域化 + 按域装配拆分 + 配置类拆分，starter +3 装配门控测试；
  更早基线 2026-08-10 B1+B2+B3+B4：B1 租户地基 starter +14 / admin +11，B2 水平扩展 starter +15，B3 配额计费 starter +12，B4 配置版本化 admin +9。
  **上面的基线数是本机起了 MySQL 跑出来的**（B6 实测 starter 5 skip / admin 1 skip）；MySQL 不可达时
  starter 会跳到 113 skip、admin 少跑门控用例显示 721，属正常。本机没有 MySQL 时可用
  `docker run -d --name cw-mysql-test -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=agent_scope_customer_work -p 3306:3306 mysql:8.0`
  起一个，再 `CREATE DATABASE customer_admin`（admin 的 Flyway 只建表不建库，缺库会让 2 个用例
  直接报错而不是跳过——比 MySQL 完全不可达更难判断）。
  上一版基线是 2026-08-06 的 starter 1136 + admin 722；更早是 2026-08-05 的 starter 1054 + admin 711；
  admin-server 更早的实际基线已是 707，CLAUDE.md 曾记的
  701 系陈旧数字。starter 已按下方规则排除 2 个环境门控测试。此前 feature/sink-common-to-starter 把 admin
  十项通用能力下沉 starter：核心逻辑测试随迁，故 starter +226 / admin −106，admin 侧只保留薄壳职责测试；
  PR #68 修掉多构造器缺 @Autowired 后 `ApplicationContextTest` 已恢复，不再需要额外排除）。
  门控集成测试依赖：PaddleOCR serving(localhost:8868)、MinIO(localhost:9000)，不可达自动跳过。
  外部依赖门控测试：MySQL(root/root)、Redis(密码 123456)、Nacos(nacos/nacos:8848)，不可达自动跳过。
- **本机跑全量的环境门控**：Redis 无密码时 `RedisSessionPersistenceTest` 必挂，需排除
  （starter 一挂会让下游模块整体 skip，`-fae` 也救不回来）：
  `-Dtest='!RedisSessionPersistenceTest' -DfailIfNoSpecifiedTests=false`
  MinIO 起在 9000 时 `MinioAttachmentFileStorageIntegrationTest` 可以正常跑，不必再排除；
  9000 被别的栈（如 kb-rag）占用时才需要连它一起排除。
- 模块 A 改完给模块 B 用时，先 `mvn install -Dmaven.test.skip=true -Djacoco.skip=true`（B 解析的是本地仓库的 jar，
  不是 A 的工作树）；根 pom 变更后父 POM 也要 `mvn -N install`，否则 B 读到旧版本号。

## 文档地图（哪个问题去读哪个文档）

- 项目概览/模块说明/全景架构图 → `README.md`
- 多租户隔离模型/逐表归属/身份链路 → `docs/多租户架构设计.md`
- 数据权限（角色数据范围/仅本人过滤/白名单逐表判定）→ `docs/数据权限设计.md`
- 功能总表/配置项/接口速查/各功能用法 → `docs/功能与配置全量参考.md`
- 1.x→2.0 API 映射、RC4→GA 变更、issue 重新核对 → `docs/MIGRATION-2.0.md`
- 框架 open issues 与本项目链路的交叉评估 → `docs/生产就绪评估.md`
- 生产部署步骤/环境变量/灰度回滚 → `docs/部署手册.md`
- 架构原理/时序图/UML → `docs/详细技术文档.md`
- 用户工单系统（7 态状态机/WS 帧协议/用户端/坐席端）→ `docs/详细技术文档.md` 工单章节 + `docs/生产接口使用手册.md`
- 后台管理系统需求 → `docs/AI编码助手需求文档.md`（admin-server 相关）

## 项目编码规范（全局规范之外的项目特有约定）

- **【最高优先级】构建 Agent 一律走 `AgentGovernanceAssembler`**：本项目最顽固的缺陷形状是
  「能力只接在用户不走的那条路上」，已复发六次（语义缓存只接非流式、CSAT 挂错生命周期钩子、
  缓存命中的出站过滤只在流式落实、知识盲区埋点只覆盖工具路径、附件 OCR 绕开 Spotlighter、
  `/consult` 整条链路无中间件）。根因是「装配」这件事散落在多个入口各写一遍，改了这处忘了那处，
  **而两边都不会报错**。现在收敛为唯一入口：
  ① 任何 `ReActAgent.builder()` 在 `build()` 前必须调 `governanceAssembler.applyTo(builder)`；
  ② 新增治理中间件只改 `AgentGovernanceAssembler` 一处，所有路径自动获得；
  ③ `AgentAssemblyAlignmentTest` 扫描源码对此下断言——**新增一条建 Agent 的路径而不装配就会红**；
  ④ admin 不走装配器（它 exclude 了 starter 自动装配），完整性由该测试的第二个用例单独盯。
  改动前先跑这个测试，它是这类缺陷唯一的机器防线。
- **同一个字面量只允许有一个定义处**：这是「多个真相来源」的另一种形态，成因和上一条同源。
  `private static final String STORE_MODE_JDBC = "jdbc"` 曾在 21 个装配类里各写一遍，
  连同 `MODE_JDBC` / `JDBC` / 裸字面量共 28 处表达同一个 "jdbc" 语义。收敛后的落点：
  ① 跨域公共值放 `customerwork.core.constant`（`StoreModes` / `ModelProviders` / `AgentFileNames` /
  `HttpAuthConstants` / `OpenApiProtocol` / `FactTypes` / `DevDefaultCredentials`），
  admin 侧放 `customeradmin.common.constant`（`AgentCapabilities` / `SystemRoles` /
  `StatsGranularity` / `StarterMapperXml`）；② 只在一个域里用的放该域内（`OrderStatuses` /
  `DevToolConstants` / `ToolConstants` / `EvalErrorCodes`）；③ **标准库/Spring 已有的不要自己造**
  （`Authorization`、`Content-Type` 用 `HttpHeaders`，`application/json`、`application/octet-stream`
  用 `MediaType`）；④ 跨模块共享的值定义在双方的公共依赖 starter 上，不要靠"两边各写一份、口头保持一致"
  （`ChatModelProber` 曾在注释里写"与 admin 的 ModelProvider 编码一致"，而没有任何机制保证）。
  **判定标准是概念而不是值**：两处如果其中一处改了值而另一处没改会出错，才是同一个概念；
  否则是巧合（`FAILED` 分属五个状态机、`http` 既是 URL scheme 又是 MCP 传输类型），
  合并反而把不相干的东西绑死。`SharedConstantAlignmentTest` 扫描四个模块源码对此下断言——
  已收敛的值在别处重新声明、或任何新值出现在两个以上文件，都会红；确属巧合的加进
  `DISTINCT_CONCEPTS` 并写明理由。
  **数值常量同理但归属不同**：库表列值归它自己的实体或域常量类，别往公共包塞——
  `sys_operation_log.result` 与 `ai_coding_audit_log.result` 都是 `1=成功`，却是两张表两套语义
  （本批次差点合并，编译期才发现 `log` 变量根本不是同一个实体）。通用的只有
  `StatusFlags.ENABLED`（18 处曾各写一遍的 `status=1`）与 `TreeConstants.ROOT_PARENT_ID`。
  相反，**独立参数碰巧同值的不要合**：三处 `MAX_BACKOFF_SHIFT=10`、两处 `SHUTDOWN_WAIT_SECONDS=5`
  是各自组件的调优参数，合了会让"改 outbox 退避"意外改到死信队列。
  **`@ConfigurationProperties` 的字段默认值刻意保持字面量**：`spring-boot-configuration-processor`
  只从字面量初始化表达式提取 `defaultValue`，改成常量引用会让那 336 项默认值元数据静默消失。
- **改动"某条链路的能力"时，先列出全部同类链路再动手**。当前共 7 条：
  `chat()` / `chatStream()` / WS `/ws/user` / `/consult` 多 Agent / admin 工作台 / customer-channel / Harness。
  只在一条上验证通过 ≠ 修好了——前六次复发都是这么来的。
- **yml 只写三类东西，默认值一律只写 Java**：① `${ENV:}` 环境变量占位；
  ② 与 Java 默认值<b>刻意不同</b>的覆盖（写注释说明为什么）；③ Spring 自身配置（`server.port` 等无属性类可依托的）。
  把 `@ConfigurationProperties` 的默认值在 yml 里再抄一遍 = 默认值有两个真相来源，改 Java 时 yml 不跟，
  **而实际生效的是 yml**（曾出现 `security.rate-limit.rule-enabled` yml=true / Java=false）。
  app-server 的 yml 据此从 571 行降到 193 行、264 个配置项降到 67 个。
  "有哪些项可配、默认多少"由 `spring-boot-configuration-processor` 生成的元数据回答（IDE 自动补全 + 中文 javadoc + 默认值，
  当前收录 371 项）——给 `CustomerWorkProperties` 的域字段加 `@NestedConfigurationProperty` 才会被收录，新增域别忘了加。
  `YmlTrimEquivalenceTest` 用 Spring 的 `Binder` 比对瘦身前后的绑定结果，误删会立刻红。
  例外：prod profile 里的**生产安全基线**（如 `skill.code-execution-enabled: false`）即使与默认值相同也显式声明，
  那表达的是"生产明确要求它是关的"，有人改了 Java 默认值时仍守得住——这类项旁边都写了理由。
- **admin 访问客服端库一律走 `CustomerWorkFacade`**：惰性建池、探测、库不可达转业务异常、销毁关池
  这套固定套路此前 8 个能力域各抄一份（~560 行），改池参数或异常口径要记得改 8 处，
  新增时最容易"照着抄但漏了 `@PreDestroy`"——漏了不报错，只在反复重启时慢慢泄漏连接池。
  现在全 admin 只有 `CustomerWorkFacade` 一处调 `CrossDbGateways.lazy`，新增门面只需填 5 个参数。
  连接信息走 `CustomerWorkDbConnection` 接口而非某个具体属性类——**9 个门面里有 6 个复用
  `admin.content-guard.*`，字典用 `admin.dict.*`、调用统计用 `admin.agent-call-stats.app.*`**。
  **批量模板化重构这批文件时踩了两个坑（都只在全量测试才暴露）**：
  ① 想当然地把属性类统一成 `ContentGuardProperties`，用自有属性类的那两个编译不过；
  ② 模板只保留了 javadoc，丢掉了个别文件才有的 `@EnableConfigurationProperties(XxxProperties.class)`——
  那是这些属性类**唯一**的注册入口（它们只有 `@ConfigurationProperties`、没有 `@Component`），
  丢了之后编译照常通过，直到启动时报 `NoSuchBeanDefinitionException`。
  改这类文件前先 `git show HEAD:<file> | grep -E "^@[A-Z]"` 把类级注解列出来逐个对。
- **starter 里的 `WebFilter` 必须带 `@ConditionalOnWebApplication(type = REACTIVE)`**：
  admin 是 Servlet 栈，没有这个条件它就只能整体 `exclude` starter 入口自动装配来躲开这些 Bean，
  而 `OnCustomerWorkEntryCondition` 会让 8 个域装配一并让位。
  相应地，starter 里断言 WebFilter 装配的测试要用 `ReactiveWebApplicationContextRunner`
  而非 `ApplicationContextRunner`——测试上下文类型必须与真实运行环境一致，否则断言的"装配完整"与线上不是一回事。
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
- **客服端库（`cw_*` 表）没有任何加列机制**：SchemaInitializer 执行的是 `CREATE TABLE IF NOT EXISTS`，
  对**已存在**的表既不加列也不报错。空库首次启动没问题，但开发期途中给 `cw_*` 加了列的话，
  本机已建好的旧表**不会**跟着变——代码里 DO 多了字段、XML 多了列名，启动一切正常，
  一调接口就 `Unknown column` 报 500。改完 schema 请手工 `ALTER TABLE` 同步本机库，
  或直接 drop 掉那张表让它重建。B6 踩过：`cw_eval_run` 的 `seq`/`prompt_fingerprint` 是中途加的，
  先启动过的库里没有，评测接口一调就 500。（admin 库不受影响，那边有 Flyway。）
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
  混用会让控制面用户切视角后当场失去全部权限。缺上下文时持久层 fail-closed 抛错，这是刻意的。
  `ai_model_config` 是例外的租户忽略表：管理面统一走 `ModelConfigService`，运行时统一走
  `ModelConfigAccess`，禁止业务代码直接注入 `AiModelConfigMapper`；异步消费前还必须显式传播
  `TenantContext`，否则运行时访问器会 fail-closed。
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
- **存储落库（B5 起）**：项目内**不落盘**——结构化信息进 MySQL、文件进 MinIO，本地盘实现已全部删除。
  四条约定：
  ① **三层记忆全部落库**：L2 `cw_long_term_memory`、L3 `cw_fact_log`、Harness 分层记忆 `cw_harness_memory`，
  三处 `store-mode` 默认 `jdbc`；改这些默认值必须同步改 `PersistenceJdbcCondition.JDBC_BY_DEFAULT_KEYS`——
  该条件读原始 `Environment`，**看不见 properties 类里的 Java 默认值**，漏登记会出现"Store 想用 jdbc
  但持久化环境没激活、Mapper 取不到"的错配；
  ② **框架只认文件的地方，MySQL 当权威、磁盘当可重建缓存**：`FileSystemSkillRepository` 与 Harness
  的 `MEMORY.md` 都只读文件系统，故技能物化目录每次启动全量重建（先清空再写）、MEMORY.md 由
  `HarnessMemorySyncService` 水合/回写。别把落盘本身当成"没改成 MySQL"；
  ③ **文件一律走 `AttachmentFileStorage` SPI（只有 MinIO 一种实现）**，别再 `Files.write`；
  本地盘实现已整个删除而非留作降级——留着只会让人在不知情时踩进去。MinIO 不可达时上传直接失败，
  这是刻意的；
  ④ **项目内不出现 `./data/`**：框架硬约束的工作目录（Harness workspace、技能物化目录、
  代码执行沙箱、XXL-JOB 执行器日志、VibeCoding workspace）统一走 `RuntimeWorkDir` 落
  `${java.io.tmpdir}/customer-work/`——里面全是可随时重建的派生物，放项目目录会让人误以为要备份。
  新增这类目录时用 `RuntimeWorkDir.of(...)`，不要再写字面量路径。
- **运营闭环（B6 起）**：把"造好没接线"的能力接上入口，八条链路的关键约定：
  ① **评测跑在客服端、后台只做触发与展示**：评的必须是线上真实那一套（同一 orchestrator/提示词/模型链），
  admin 侧实现一遍等价逻辑等于评了个副本。admin 经跨库门面**直接复用 starter 的 Store 与领域对象**
  （`MybatisEvalRunStore`/`BadcaseService`），不重写解析与判定——两边对同一行数据算出不同结果是最难查的 bug；
  ② **取"上一次"一律按写入顺序（自增 `seq`）而非 `created_at_ms`**：评测是纯内存计算，
  连跑两次会落在同一毫秒，按时间戳取基线会让第二次找不到上一版，CI 里必踩（`cw_fact_log` 早有此约定）；
  ③ **语义缓存默认关闭且只缓存通用问答**：无差别缓存会把 A 用户的订单信息返回给 B。三道闸门收口在
  `SemanticCacheService#cacheable` 一处——意图白名单（默认仅 `consult`）+ 个人标识过滤（6 位以上连续数字）
  + 双层隔离。放宽前先回答"两个不同用户问同一句话，答案是否必然相同"。
  ③.1 **查缓存/写缓存必须两条路径都接**（2026-08-19 修）：此前只做在非流式 `chat()` 上，而用户端 H5 走的是
  WS 流式（`ChatDispatchService` → `chatStream`）——真实流量一条缓存都产生不了、一次都命中不了，
  开着开关配着 jdbc 表却永远是空的。同 CSAT 那批的病根：**能力接在了用户不走的那条路上**。
  流式侧三条约定：缓存的必须是**出站敏感词过滤之后**的文本（否则下次命中会把未过滤内容直吐）；
  命中后仍要再过一遍过滤器（写入时合规不代表现在合规，词库会变）；写缓存用 `doOnComplete` 而非
  `doFinally`，且要靠降级标志排除"半截回答 + 兜底文案"——`doFinally` 在错误路径也会跑，
  把那段缓存下来，之后每个问到同类问题的人都会收到残缺回复。
  ③.2 **语义缓存的分区键刻意保持用户级，不要跟着 CSAT 改成租户级**：那是隔离底线。
  运营看板的可用性靠 `listScopes` 把实际分区列出来给人选（`GET /api/ops/semantic-cache/scopes`），
  而不是让人手填一个猜不到的用户 ID；
  ④ **分级路由能力取交集**：`TieredRoutingModel` 的结构化输出支持性与上下文窗口按两档中较弱的报，
  路由是动态的，按主模型报会让走经济档那次当场崩；判定保守（只有单轮且简短才降级），
  判错的代价因此是"没省到钱"而非"答得差"；
  ⑤ **提示词版本用内容指纹而非外部版本号**：Nacos 下发的是内容、没有随行版本号。指纹让
  `EvalComparison#promptChanged` 成为归因支点——指标掉了先看这一位，没变就别再对着提示词逐字看；
  ⑥ **CSAT 邀请挂在工单终态、分区取租户**（2026-08-19 修）：用户端真正的结束动作走工单状态机
  （关单/确认解决），不经过 `CustomerServiceService#endSession`——只挂后者时邀请仅在空闲超时清理时发出，
  用户早已离开，分母近乎恒为 0，看板三指标全 0.0% 而链路不报错。改由 `CsatTicketInviteListener` 接
  工单事件（Outbox 投递有 1~2 秒延迟，用户端评分卡补拉一次）。另两条同源约定：
  评测/合成监控走 `discardSession` 而非 `endSession`（背后没真人，计进去等于用空邀请稀释回收率）；
  **运营分区走 `OpsScopeResolver`（取租户）而不是 `TenantResolver`（取 sessionId 前缀）**——
  后者在用户端解析出的是用户（`u{userId}:conv-xxx`），隔离类数据要的正是这个，
  但运营指标按用户分区等于每人一张报表。CSAT 与知识盲区两个看板长期空白就是这么来的。
  ⑥.1 **CSAT 必须邀请与回收分开记**：只记评分算不出回收率，而回收率低时那个漂亮的分数只代表
  愿意评价的一小撮人。满意按 4 分及以上算（行业口径），不是平均分；
  ⑦ **知识未命中文案是接口契约**（`KnowledgeBackend.NO_HIT_REPLY` + `isMiss`）：盲区埋点据此判定，
  此前两个实现各自硬编码同一句话，改文案会让统计静默失效；
  ⑧ **死信重试耗尽转 ABANDONED 而不删**，退避必须指数（下游多半在重启，密集重试是自制雪崩）；
  没注册 handler 的类型跳过且**不累计次数**——累计会让它悄悄耗尽，掩盖"这类压根没人处理"。
- **主体级速率配额（B7 起）**：按**调用者**限流（每登录用户 / 每匿名 IP / 每把 API Key），
  滚动窗口内的 token 量与请求次数双上限，默认关闭（`customer-work.subject-quota.enabled`）。
  与 B3 租户配额并存互不替代：那边是"这个客户这个月能花多少钱"（自然日/月对齐，要跟账单对得上），
  这边是"这个调用者这半小时能用多少"（滚动窗口，防滥用）。九条约定：
  ① **判定只读、放行后才记账**：`check` 不写计数，通过后 `recordRequest`、模型调用后由
  `AgentCallTimingMiddleware`（token 唯一落点）补 `recordTokens`。因此被拒的请求不占额度，
  持续打压不会把窗口越推越远；代价是并发下允许少量超额，这是刻意取舍；
  ② **主体身份必须走 `QuotaSubjectContext`**（ThreadLocal + Reactor 自动传播，机制同 `TenantContext`）：
  token 用量要到模型调用后才知道，那时已隔了几次线程切换，方法签名里没有"用户"参数。
  写入方必须**同时**写 ThreadLocal 与 Reactor Context，只写一个会在某类链路上拿不到主体；
  ③ **`SubjectQuotaWebFilter` 的 Order 必须排在两个鉴权过滤器之后**（`+30` > ApiKey `+10` > UserAuth `+20`），
  抢在前面会把登录用户全按匿名 IP 限。它还会自己验一次 Bearer——`UserAuthWebFilter` 只覆盖
  `/api/customer/user/**`，不自己验的话换条路径就能从"按人限"退化成"按 IP 限"；
  ④ **WS 入口必须单独判**：H5 用户真正的发消息入口是 `/ws/user`，WebFilter 管不到，
  只做 HTTP 侧等于对主战场不生效。判定与记账都要包在 `TenantContext.callWith(用户租户)` 里——
  等级表按租户隔离，拿错租户会查到别人那一档；
  ⑤ **token 是"量"不是"次"**：`WindowCounter#incrementSlidingSum` 用 30 桶近似滑动窗口
  （逐条记时间戳在高 QPS 下会吃光内存/Redis），统计范围刻意**不短于**名义窗口（多留一桶），
  误差方向必须 fail-closed；口径计算在 `SlidingSumBuckets`，两个实现共用（降级时要能对得上）；
  ⑥ **API Key 只留 SHA-256 指纹**：主体标识会进 Redis 键、命中表与日志，明文落任何一处都是凭据泄露；
  ⑦ **额度自查接口 `/api/customer/user/quota` 必须豁免判定**：不豁免则查一次扣一次，
  且额度耗尽后连"还剩多少"都看不到——偏偏那正是最需要它的时刻；
  ⑧ **超限落"命中记录"而不是实时余额**：余额在计数器里（跨进程读不到），而运营要回答的是
  "谁在刷、哪档配紧了"。只在触顶那一刻写一条，正常流量零写入；
  ⑨ **生效延迟 60 秒是设计的一部分**（等级快照指纹轮询 + 绑定本地缓存）：不缓存的话每个请求
  都要查一次用户表，限流本身会成为最重的一段。**但写侧要主动失效**——后台保存等级/分配档位后
  直接 `reload()` + `evictBinding()`，让本进程立即生效；跨进程（客服端）与多副本仍走轮询。
  后台页面必须把"哪些立即生效、哪些要等"写给运营看。
  新增的 `subject-quota.store-mode` 已登记进 `PersistenceJdbcCondition`（漏登记会出现
  "Store 想用 jdbc 但持久化环境没激活"的错配）。
  **后台登录用户同样纳入（`admin.subject-quota.enabled`，默认关闭）**，六条补充约定：
  ① **主体类型单列 `ADMIN_USER`**：`sys_user` 与 `cw_user` 是两套 ID 空间，同一个 ID 值指的是
  不同的人，混用 `USER` 会让计数键碰撞（管理员与终端用户共用一份额度，且查不出原因）；
  等级定义共用 `cw_subject_quota_level`（靠 `subject_type` 区分），绑定落 `sys_user.level_code`；
  ② **判定用 MVC `HandlerInterceptor`**：admin 是 Servlet 不是 WebFlux，没有 `WebFilter`。
  超限返回 429 + `Retry-After`，错误码单列 `QUOTA_EXCEEDED(40043)`——额度用尽既不是没权限也不是参数错，
  混进既有码会让前端提示"联系管理员开权限"；
  ③ **等级快照走惰性刷新**（`SubjectQuotaLevelProvider` 的三参构造）：admin 刻意不开
  `@EnableScheduling`，客服端那套 `@Scheduled` 轮询在这边根本不会跑，不改成读路径刷新的话
  后台改完等级永远不生效；
  ④ **上下文传播只接主体、不搭车改租户**：admin 此前完全没有 Reactor 上下文传播（starter 自动装配被
  exclude）。补租户会让多租户开启后 AI 链路的持久层操作开始真正带过滤，那是独立的行为变更，
  该单独评估。自动传播是全局静态开关，故只在 `enabled=true` 时打开——代价是开配置要重启；
  ⑤ **路径清单刻意比 C 端窄**（只覆盖 `chat/stream`、`vibecoding`、`agent-task` 三条真正调模型的入口）：
  后台用户是内部员工，防的是"调用失控"而不是"有人刷接口"，把翻列表也算进去只会让人干不了活；
  ⑥ **委派任务要在提交那一刻捕获主体**（`MybatisTaskRepository#putTask`）：它跑在自建线程池上，
  Reactor 的自动传播覆盖不到，不用 `QuotaSubjectContext.callWith` 带进去的话，
  那条链路烧的 token 就没有主人（额度里只有"提交次数"在动）；
  ⑦ **admin 侧写 SQL 条件不要用 MyBatis-Plus 的 lambda wrapper**：lambda 解析依赖 `TableInfo` 缓存，
  不启动容器的单测里拿不到，会直接抛 `MybatisPlusException`；跨库门面场景同样没有拦截器，
  一律用字符串列名（`new QueryWrapper<>().eq("id", x)`）；
  ⑧ **加 Flyway 迁移前先扫一遍各 worktree 的最大版本号**：并行开发时版本号极易撞车
  （本批次的 V56 就撞上了另一个分支已 apply 到本机库的 V56，表现为 checksum mismatch 启动失败）。
  本机库被别的分支迁移污染时，用一个临时库验证自己的迁移与上下文加载，别去动共享库的 history。
- **数据权限（用户维度隔离）**：叠加在 B1 租户过滤之上的第二道行级过滤，回答"这个租户里，谁的数据"。
  范围挂在角色上（`sys_role.data_scope` = ALL/TENANT/SELF），强制点是 `DataScopeInnerInterceptor`。
  设计全文见 `docs/数据权限设计.md`，六条硬约定：
  ① **白名单，方向与租户维度相反**：租户维度"能加列的全加"，漏一张就串数据；用户维度绝大多数表是
  租户内共享的配置资产，误加一张会让同租户成员协作断掉（A 建的智能体 B 用不了），且**不报错**，
  只表现为"数据莫名其妙少了"。新表默认不进 `DataScopeTables`，除非它确实是"某个人的产出物"；
  ② **没有上下文时不过滤，与租户维度的 fail-closed 刻意相反**：数据范围缺失只出现在压根没有"人"的
  链路上（调度线程、异步回调、开放 API），fail-closed 只会把后台任务整体打挂，而跨租户那道防线还在。
  例外是凭令牌进入的链路——令牌背后是确切的人，必须 `DataScopeContext.callAs` 显式还原身份，
  否则 A 的 ScriptCat 脚本能读到 B 录的站点密码；
  ③ **`归属列 IS NULL` 视为租户内共享**：存量数据没有归属人，一并挡掉会让升级当天历史记录全部消失；
  ④ **UPDATE/DELETE 也要过滤**：只管列表页的话，用户看不到别人的行却能凭 ID 改删（`updateById`
  只按主键定位），这是实打实的越权；INSERT 反而不管，归属列由 `MyMetaObjectHandler` 一处写入；
  ⑤ **`ALL` 必须同时校验当前用户的控制面角色**：租户管理员能建角色，只认字段值等于让任意租户
  自己给自己开跨租户的口子；跨租户操作还必须叠加原有权限点，统一走 `CrossTenantAuthority`。
  `control_plane` 不开放给普通角色编辑接口，控制面角色的分配、移除、恢复、编辑或删除也只允许已有控制面用户；
  控制面专属权限统一由 `ControlPlanePermissions` 定义，同时约束租户开通、权限树与角色保存；
  Controller 仍须叠加 `CrossTenantAuthority`，不能把角色权限关系当成唯一安全边界。敏感词过滤器完成按租户分片前，
  其五个写入口同样按控制面能力收口，避免任一租户的高频词影响全局；
  ⑥ **对话会话复用既有的 `ai_workspace_session`，不另建归属表**：框架状态表加不了列，归属由
  `WorkspaceSessionGuard` 维护。本批次只给它接上范围——`SELF` 只放行自己认领的会话，
  `TENANT`/`ALL` 只校验会话存在于当前租户（超管要能看全量）。该表不进白名单：归属条件已在
  那条 JOIN 里显式表达，且它的归属列叫 `owner_user_id`，不在白名单支持的两种列名之内。
- **`admin.tenant.enabled` 从本批次起默认 `true`**（此前默认关闭 = 跨租户完全打通）。开关一开，
  凡是**不在 Web 请求里**的查库都会因缺租户上下文 fail-closed。四类都要显式处理，改动时别退回去：
  ① **无登录态的 HTTP 链路**：开放 API 走 `admin.open-api.tenant-tokens` 的令牌→租户映射、
  工作台脚本回调从令牌行读租户、登录页轮播图归入全局级忽略清单（登录前无上下文可用）；
  ② **调度线程与轮询守护线程**：内置调度器/XXL-JOB 走 `executeFromScheduler`（跨租户定位 + 按任务租户
  还原上下文）；内容风控词库刷新（`SensitiveWordRefreshDriver` 的守护线程）走 `CrossTenantOperations`
  加载全量词库——**它读失败会让过滤器 fail-closed"拦截一切"，后台对话全被拦**，而异常被 Store 的
  catch 吞成一行日志，很难联想到租户开关。代价是进程级单例过滤器用所有租户词表的并集，可能误拦，
  方向上安全优先；要按租户精确过滤得把过滤器改成分片，那是独立的一件事；
  ③ **启动期装配**：`@Bean` 工厂方法、`@PostConstruct`、`ContextRefreshedEvent` 里的查库。
  **这一类最容易漏且后果最重——A2A 的 `@Bean` 里查 `ai_agent` 直接让应用起不来**（实测踩过）；
  `contextLoads` 照不出来，因为那条装配路径默认关着。判断依据：这类查询要么是全局唯一键定位
  （靠全局唯一的 `agent_code`/`task_code` 跨租户找一条），要么是跨租户运维扫描
  （重启清理孤儿任务），两者都走 `CrossTenantOperations`；
  ④ **异步回调**：模型连通性测试等跑在独立线程池里的落库。
  写这类测试时**别断言"没抛异常"**——单测里本就没挂拦截器，怎么写都不会抛，那种断言恒真；
  要断言 `InterceptorIgnoreHelper.willIgnoreTenantLine(...)` 在查询发生的那一刻为真
  （见 `AdminA2aServerConfigTenantTest`），并且退出作用域后为假。
- **本机开发库是所有分支共用的，Flyway 版本号常年被并行分支占走**：新增迁移前先查
  `SELECT MAX(version) FROM customer_admin.flyway_schema_history`，而不是只看当前分支的文件名——
  本批次就先后被 V55（已合入 main）、V56~V58（未合并的并行分支）挤到 V59/V60。
  另注意 Flyway 默认忽略"版本号高于本地最高版本"的库内记录，所以**本地号一旦超过它们，
  那些记录会从 future 突变成 missing 直接拒绝启动**；dev profile 已配
  `ignore-migration-patterns: "*:missing"` 容忍这种切分支场景，生产 profile 刻意不配。
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
